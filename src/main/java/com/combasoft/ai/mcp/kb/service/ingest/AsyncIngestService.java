package com.combasoft.ai.mcp.kb.service.ingest;

import com.combasoft.ai.mcp.kb.config.QdrantConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
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
    private static final int BATCH_SIZE = 10; // Сохраняем небольшими порциями
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    // 📦 Простой in-memory кэш: Path -> {result, timestamp}
    private final Map<String, CacheEntry> duplicateCheckCache = new ConcurrentHashMap<>();
    private record CacheEntry(boolean exists, Instant timestamp) {}


    private final IngestService ingestService;
    private final IngestionProgressTracker tracker;
    private final QdrantClient qdrantClient;


    private final String collectionName;

    public AsyncIngestService(IngestService ingestService, IngestionProgressTracker tracker,
                              QdrantClient qdrantClient, QdrantConfig qdrantConfig) {
        this.ingestService = ingestService;
        this.tracker = tracker;
        this.qdrantClient = qdrantClient;
        this.collectionName = qdrantConfig.getCollectionName();
    }

    @Async
    public String ingestDocumentAsync(String sourcePath, Map<String, Object> metadata) {
        IngestionTask task = tracker.createTask(sourcePath);
        log.info("🚀 Starting async ingestion for: {}", sourcePath);

        try {
            Path path = Path.of(sourcePath);

            // 1. Валидация дубликатов (теперь с кэшем)
            if (isDocumentIndexed(path)) {
                tracker.updateTask(task.taskId(), t -> t.failed("Document already exists"));
                return task.taskId();
            }

            tracker.updateTask(task.taskId(), IngestionTask::started);

            // 2. Подготовка (Чтение + Чанкинг)
            List<Document> allDocs = ingestService.prepareDocuments(path, metadata);
            if (allDocs.isEmpty()) throw new IllegalStateException("No content to index");

            tracker.updateTask(task.taskId(), t -> t.setTotalChunks(allDocs.size()));

            // 3. Пакетное сохранение
            for (int i = 0; i < allDocs.size(); i += BATCH_SIZE) {
                List<Document> batch = allDocs.subList(i, Math.min(i + BATCH_SIZE, allDocs.size()));
                ingestService.storeDocuments(batch);

                // Обновляем прогресс
                int processed = i + batch.size();
                tracker.updateTask(task.taskId(), t -> t.updateProgress(processed));
            }

            tracker.updateTask(task.taskId(), IngestionTask::completed);
            log.info("✅ Async ingestion completed: {}", sourcePath);

        } catch (Exception e) {
            log.error("❌ Async ingestion failed: {}", e.getMessage(), e);
            tracker.updateTask(task.taskId(), t -> t.failed(e.getMessage()));
        }
        return task.taskId();
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

        // 🧹 Обязательно чистим кэш после удаления, иначе следующая загрузка этого файла упадёт
        invalidateCache(path);
    }

    @Async
    public void updateDocument(String sourcePath, Map<String, Object> metadata) {
        try {
            deleteDocument(sourcePath); // Удаление само почистит кэш
            Thread.sleep(200);
            ingestDocumentAsync(sourcePath, metadata);
        } catch (Exception e) {
            log.error("Update failed: {}", e.getMessage());
        }
    }

    /**
     * Проверяет наличие документа в БД с использованием кэша.
     */
    private boolean isDocumentIndexed(Path path) {
        String source = path.toAbsolutePath().normalize().toString();

        // 1. Проверяем кэш
        CacheEntry entry = duplicateCheckCache.get(source);
        if (entry != null && Instant.now().isBefore(entry.timestamp().plus(CACHE_TTL))) {
            log.debug("📦 Cache hit for: {}", source);
            return entry.exists();
        }

        // 2. Cache Miss -> Запрос к Qdrant
        boolean result = queryQdrant(path, source);

        // 3. Сохраняем в кэш
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
            return false; // Fail-open
        }
    }

    /**
     * Принудительная очистка кэша для конкретного пути.
     */
    private void invalidateCache(Path path) {
        String source = path.toAbsolutePath().normalize().toString();
        duplicateCheckCache.remove(source);
        log.debug("🧨 Cache invalidated for: {}", source);
    }
}