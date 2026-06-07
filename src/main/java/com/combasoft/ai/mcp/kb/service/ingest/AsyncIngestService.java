package com.combasoft.ai.mcp.kb.service.ingest;

import com.combasoft.ai.mcp.kb.config.QdrantConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AsyncIngestService {
    private static final Logger log = LoggerFactory.getLogger(AsyncIngestService.class);
    private static final int BATCH_SIZE = 32;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final Map<String, CacheEntry> duplicateCheckCache = new ConcurrentHashMap<>();
    private record CacheEntry(boolean exists, Instant timestamp) {}

    private final IngestService ingestService;
    private final IngestionProgressTracker tracker;
    private final QdrantClient qdrantClient;
    private final String collectionName;

    // 🔑 Инжектируем сами себя через @Lazy, чтобы вызывать @Async методы через прокси (избегаем self-invocation trap)
    private final AsyncIngestService self;

    public AsyncIngestService(IngestService ingestService, IngestionProgressTracker tracker,
                              QdrantClient qdrantClient, QdrantConfig qdrantConfig,
                              @Lazy AsyncIngestService self) {
        this.ingestService = ingestService;
        this.tracker = tracker;
        this.qdrantClient = qdrantClient;
        this.collectionName = qdrantConfig.getCollectionName();
        this.self = self;
    }

    /**
     * 🔽 СИНХРОННЫЙ МЕТОД: Создает задачу, возвращает taskId и передает выполнение в фон.
     */
    public String startIngestion(String sourcePath, Map<String, Object> metadata) {
        IngestionTask task = tracker.createTask(sourcePath);
        log.info("🚀 Queued async ingestion for: {} (taskId: {})", sourcePath, task.taskId());

        // Вызываем через self (прокси), чтобы Spring AOP гарантированно запустил метод в отдельном потоке
        self.executeIngestionAsync(task.taskId(), sourcePath, metadata);

        return task.taskId();
    }

    /**
     * 🔽 АСИНХРОННЫЙ МЕТОД: Выполняет тяжелую работу. Возвращает CompletableFuture<Void>.
     */
    @Async
    public void executeIngestionAsync(String taskId, String sourcePath, Map<String, Object> metadata) {
        log.info("🏃 Starting actual ingestion execution for taskId: {}", taskId);
        try {
            Path path = Path.of(sourcePath);

            if (isDocumentIndexed(path)) {
                tracker.updateTask(taskId, t -> t.failed("Document already exists"));
                return;
            }

            tracker.updateTask(taskId, IngestionTask::started);

            List<Document> allDocs = ingestService.prepareDocuments(path, metadata);
            if (allDocs.isEmpty()) throw new IllegalStateException("No content to index");

            tracker.updateTask(taskId, t -> t.setTotalChunks(allDocs.size()));

            for (int i = 0; i < allDocs.size(); i += BATCH_SIZE) {
                List<Document> batch = allDocs.subList(i, Math.min(i + BATCH_SIZE, allDocs.size()));
                ingestService.storeDocuments(batch);

                int processed = i + batch.size();
                tracker.updateTask(taskId, t -> t.updateProgress(processed));
            }

            tracker.updateTask(taskId, IngestionTask::completed);
            log.info("✅ Async ingestion completed for taskId: {}", taskId);

        } catch (Exception e) {
            log.error("❌ Async ingestion failed for taskId: {}", taskId, e);
            tracker.updateTask(taskId, t -> t.failed(e.getMessage()));;
        }
    }

    public void deleteDocument(String sourcePath) throws Exception {
        Path path = Path.of(sourcePath).toAbsolutePath();
        log.info("🗑️ Deleting document: {}", path);

        var filter = Points.Filter.newBuilder()
                .addMust(Points.Condition.newBuilder()
                        .setField(Points.FieldCondition.newBuilder()
                                .setKey("source")
                                .setMatch(Points.Match.newBuilder().setText(path.toString()).build())
                                .build())
                        .build())
                .build();

        qdrantClient.deleteAsync(collectionName, filter).get();
        invalidateCache(path);
    }

    @Async
    public void updateDocument(String sourcePath, Map<String, Object> metadata) {
        try {
            deleteDocument(sourcePath);
            Thread.sleep(200);
            // Создаем новую задачу для обновления и запускаем её асинхронно через прокси
            IngestionTask task = tracker.createTask(sourcePath + "_update");
            self.executeIngestionAsync(task.taskId(), sourcePath, metadata);
        } catch (Exception e) {
            log.error("Update failed: {}", e.getMessage());
        }
    }

    private boolean isDocumentIndexed(Path path) {
        String source = path.toAbsolutePath().normalize().toString();
        CacheEntry entry = duplicateCheckCache.get(source);
        if (entry != null && Instant.now().isBefore(entry.timestamp().plus(CACHE_TTL))) {
            log.debug("📦 Cache hit for: {}", source);
            return entry.exists();
        }
        boolean result = queryQdrant(path, source);
        duplicateCheckCache.put(source, new CacheEntry(result, Instant.now()));
        return result;
    }

    private boolean queryQdrant(Path path, String source) {
        try {
            if (qdrantClient == null) {
                log.warn("QdrantClient is null. Skipping check.");
                return false;
            }
            var filter = Points.Filter.newBuilder()
                    .addMust(Points.Condition.newBuilder()
                            .setField(Points.FieldCondition.newBuilder()
                                    .setKey("source")
                                    .setMatch(Points.Match.newBuilder().setText(source).build())
                                    .build())
                            .build())
                    .build();

            var response = qdrantClient.countAsync(collectionName, filter, true)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            return response != null && response > 0;
        } catch (Exception e) {
            log.warn("Qdrant check failed for '{}': {}. Assuming NOT indexed.", path.getFileName(), e.getMessage());
            return false;
        }
    }

    private void invalidateCache(Path path) {
        String source = path.toAbsolutePath().normalize().toString();
        duplicateCheckCache.remove(source);
        log.debug("🧨 Cache invalidated for: {}", source);
    }
}