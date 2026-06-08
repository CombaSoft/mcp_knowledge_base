package com.combasoft.ai.mcp.kb.service.search;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.config.QdrantConfig;
import com.combasoft.ai.mcp.kb.service.search.reranking.Reranker;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AsyncSearchService {

    private static final Logger log = LoggerFactory.getLogger(AsyncSearchService.class);

    private final VectorStore vectorStore;
    private final KbConfig kbConfig;
    private final Reranker reranker;
    private final SearchProgressTracker tracker;
    private final AsyncSearchService self;
    private final QdrantClient qdrantClient;
    private final QdrantConfig qdrantConfig;

    public AsyncSearchService(@Qualifier("kbVectorStore") VectorStore vectorStore,
                              QdrantClient qdrantClient,
                              QdrantConfig qdrantConfig,
                              KbConfig kbConfig,
                              Reranker reranker,
                              SearchProgressTracker tracker,
                              @Lazy AsyncSearchService asyncSearchService) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
        this.reranker = reranker;
        this.tracker = tracker;
        this.self = asyncSearchService;
        this.qdrantClient = qdrantClient;
        this.qdrantConfig = qdrantConfig;
    }

    public String startSearch(String query, int limit, boolean useReranking) {
        String taskId = tracker.createTask(query, null);
        self.executeSearchAsync(taskId, query, limit, useReranking);
        return taskId;
    }

    public String startSearchInDocument(String query, String sourcePath, int limit, boolean useReranking) {
        String taskId = tracker.createTask(query, sourcePath);
        self.executeDocumentSearchAsync(taskId, query, sourcePath, limit, useReranking);
        return taskId;
    }

    @Async
    public void executeSearchAsync(String taskId, String query, int limit, boolean useReranking) {
        executeSearchLogic(taskId, query, null, limit, useReranking);
    }

    @Async
    public void executeDocumentSearchAsync(String taskId, String query, String sourcePath, int limit, boolean useReranking) {
        executeSearchLogic(taskId, query, sourcePath, limit, useReranking);
    }

    private void executeSearchLogic(String taskId, String query, String sourcePath, int limit, boolean useReranking) {
        try {
            int topK = (limit > 0) ? limit : kbConfig.getMaxSearchResults();
            int multiplier = kbConfig.getRetrievalMultiplier();
            int childTopK = topK * multiplier;

            List<String> conditions = new ArrayList<>();
            conditions.add("is_parent == 'false'");
            if (sourcePath != null && !sourcePath.isBlank()) {
                conditions.add("source == '" + sourcePath + "'");
            }
            String filterExpr = String.join(" AND ", conditions);

            // ЭТАП 1: Векторный поиск детей (20%)
            tracker.updateProgress(taskId, 20, "Выполняется векторный поиск...");
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(childTopK)
                    .similarityThreshold(kbConfig.getSimilarityThreshold())
                    .filterExpression(filterExpr)
                    .build();

            List<Document> children = vectorStore.similaritySearch(request);
            if (children.isEmpty()) {
                tracker.completeTask(taskId, List.of());
                return;
            }

            // ЭТАП 2: Дедупликация и извлечение ID родителей (50%)
            tracker.updateProgress(taskId, 50, "Группировка по родителям...");

            // Используем LinkedHashMap, чтобы сохранить порядок релевантности (первый найденный ребенок = лучший)
            LinkedHashMap<String, Document> bestChildPerParent = children.stream()
                    .collect(Collectors.toMap(
                            doc -> (String) doc.getMetadata().get("parent_id"),
                            doc -> doc,
                            (existing, replacement) -> existing, // Оставляем первого (самого релевантного)
                            LinkedHashMap::new
                    ));

            LinkedHashSet<String> parentIds = new LinkedHashSet<>(bestChildPerParent.keySet());
            List<String> topParentIds = parentIds.stream().toList();

            // ЭТАП 3: Пакетная загрузка родителей из Qdrant (70%)
            tracker.updateProgress(taskId, 70, "Загрузка полного контекста родителей...");
            List<Document> parents = fetchParentsByIds(new LinkedHashSet<>(topParentIds));

            Map<String, Document> parentMap = parents.stream()
                    .collect(Collectors.toMap(doc -> (String) doc.getMetadata().get("parent_id"), doc -> doc));

            // Собираем финальные результаты, используя текст родителя, но оценку (score) лучшего ребенка
            List<SearchService.SearchResult> initialResults = topParentIds.stream()
                    .map(id -> {
                        Document parentDoc = parentMap.get(id);
                        Document childDoc = bestChildPerParent.get(id);
                        if (parentDoc == null || childDoc == null) return null;

                        return new SearchService.SearchResult(
                                parentDoc.getId(),
                                parentDoc.getText(), // 🔑 БЕРЕМ ТЕКСТ ИЗ РОДИТЕЛЯ
                                childDoc.getScore(), // 🔑 БЕРЕМ ОЦЕНКУ ИЗ РЕБЕНКА
                                parentDoc.getMetadata()
                        );
                    })
                    .filter(Objects::nonNull)
                    .toList();

            List<SearchService.SearchResult> finalResults;

            finalResults = initialResults.stream()
                    .sorted(Comparator.comparing(SearchService.SearchResult::score).reversed())
                    .limit(topK)
                    .toList();

            // ЭТАП 4: LLM Реранкинг (90%)
            if (useReranking) {
                tracker.updateProgress(taskId, 90, "Запуск LLM реранкинга...");
                List<String> parentTexts = finalResults.stream().map(SearchService.SearchResult::content).toList();
                List<Double> rerankScores = reranker.score(query, parentTexts);

                List<SearchService.SearchResult> rerankedResults = new ArrayList<>();
                for (int i = 0; i < finalResults.size(); i++) {
                    rerankedResults.add(new SearchService.SearchResult(
                            finalResults.get(i).id(),
                            finalResults.get(i).content(),
                            rerankScores.get(i),
                            finalResults.get(i).metadata()
                    ));
                }
                finalResults = rerankedResults.stream()
                        .sorted(Comparator.comparing(SearchService.SearchResult::score).reversed())
                        .toList();
            } else {
                tracker.updateProgress(taskId, 90, "Реранкинг пропущен, сортировка по векторам...");
            }

            tracker.updateProgress(taskId, 95, "Формирование ответа...");
            tracker.completeTask(taskId, finalResults);
            log.info("✅ Async search completed for taskId: {}", taskId);

        } catch (Exception e) {
            log.error("❌ Async search failed for taskId: {}", taskId, e);
            tracker.failTask(taskId, "Ошибка при поиске: " + e.getMessage());
        }
    }

    // 🔑 МЕТОД ПАКЕТНОЙ ЗАГРУЗКИ РОДИТЕЛЕЙ
    /**
     * Пакетная загрузка родительских документов по их ID из Qdrant
     */
    private List<Document> fetchParentsByIds(LinkedHashSet<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }

        try {
            // 1. Преобразуем String UUID в gRPC PointId
            List<Points.PointId> pointIds = parentIds.stream()
                    .map(id -> Points.PointId.newBuilder().setUuid(id).build())
                    .toList();

            // 2. Собираем корректный gRPC запрос RetrievePoints
            Points.GetPoints request = Points.GetPoints.newBuilder()
                    .setCollectionName(qdrantConfig.getCollectionName())
                    .addAllIds(pointIds)
                    // Нам нужен весь payload (метаданные и текст)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    // Нам НЕ нужны сами векторные массивы (экономим память и сеть)
                    .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false).build())
                    .build();

            // 3. Выполняем асинхронный запрос и ждем результат (это нормально внутри @Async метода)
            List<Points.RetrievedPoint> points = qdrantClient.retrieveAsync(request, Duration.ofMinutes(15)).get();

            // 4. Маппим gRPC ответ в Spring AI Document
            return points.stream().map(point -> {
                String id = point.getId().getUuid();

                Map<String, Object> metadata = convertPayload(point.getPayloadMap());

                // Spring AI обычно сохраняет текст в поле "text" или "content"
                String text = (String) metadata.getOrDefault("text", metadata.getOrDefault("doc_content", ""));

                return new Document(id, text, metadata);
            }).toList();

        } catch (Exception e) {
            log.error("❌ Failed to fetch parents by IDs", e);
            throw new RuntimeException("Ошибка получения родительских документов из Qdrant", e);
        }
    }

    /**
     * Преобразует gRPC Payload Map (JsonWithInt) в обычную Java Map<String, Object>
     */
    private Map<String, Object> convertPayload(Map<String, JsonWithInt.Value> payloadMap) {
        Map<String, Object> result = new HashMap<>();

        if (payloadMap == null) {
            return result;
        }

        for (Map.Entry<String, JsonWithInt.Value> entry : payloadMap.entrySet()) {
            JsonWithInt.Value val = entry.getValue();

            // Используем надежные has...() методы для JsonWithInt.Value
            if (val.hasStringValue()) {
                result.put(entry.getKey(), val.getStringValue());
            }
            else if (val.hasIntegerValue()) {
                result.put(entry.getKey(), val.getIntegerValue());
            }
            else if (val.hasDoubleValue()) {
                result.put(entry.getKey(), val.getDoubleValue());
            }
            else if (val.hasBoolValue()) {
                result.put(entry.getKey(), val.getBoolValue());
            }
            else if (val.hasListValue()) {
                result.put(entry.getKey(), convertListValue(val.getListValue()));
            }
            else if (val.hasStructValue()) {
                // Рекурсивно обрабатываем вложенные JSON-объекты
                result.put(entry.getKey(), convertPayload(val.getStructValue().getFieldsMap()));
            }
            // Если тип не установлен (KIND_NOT_SET), просто игнорируем
        }
        return result;
    }

    /**
     * Вспомогательный метод для преобразования списка значений JsonWithInt в Java List
     */
    private List<Object> convertListValue(JsonWithInt.ListValue listValue) {
        if (listValue == null) {
            return List.of();
        }

        return listValue.getValuesList().stream()
                .map(v -> {
                    if (v.hasStringValue()) return v.getStringValue();
                    if (v.hasIntegerValue()) return v.getIntegerValue();
                    if (v.hasDoubleValue()) return v.getDoubleValue();
                    if (v.hasBoolValue()) return v.getBoolValue();
                    return v.toString(); // Fallback для неизвестных типов
                }).collect(Collectors.toList());
    }
}