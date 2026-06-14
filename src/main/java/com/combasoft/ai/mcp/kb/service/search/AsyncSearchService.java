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
                              @Qualifier("llamaReranker") Reranker reranker,
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
            double vectorSimilarityThreshold = kbConfig.getVectorSimilarityThreshold();
            int topNrerankingResults = kbConfig.getRerankTopNResults();

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
                    .similarityThreshold(vectorSimilarityThreshold)
                    .filterExpression(filterExpr)
                    .build();

            List<Document> children = vectorStore.similaritySearch(request);
            if (children.isEmpty()) {
                tracker.completeTask(taskId, List.of());
                return;
            }

            List<Document> sortedChildren = children.stream()
                    .filter(r -> Objects.nonNull(r.getScore()))
                    .sorted(Comparator.comparing(Document::getScore).reversed())
                    .toList();

            Map<Document, Double> childrenScores = new HashMap<>();

            // ЭТАП 2: LLM Реранкинг (30%)
            if (useReranking) {

                tracker.updateProgress(taskId, 30, "Запуск LLM реранкинга...");

                int limitToReranking = Math.min(sortedChildren.size(), kbConfig.getReranker().maxDocsToRerank());

                // Get top 50 for reranking
                List<Document> childrenToRerank = sortedChildren.stream()
                        .limit(limitToReranking)
                        .toList();

                List<String> documentsToRerank = childrenToRerank.stream().map(Document::getText).toList();
                List<Double> rerankScores = reranker.score(query, documentsToRerank, taskId);
                log.info("✅ taskId: {} , rerankScores {}", taskId, rerankScores);

                boolean hasError = rerankScores.stream().anyMatch(d -> d > 1.0);

                if (!hasError) {


                    for (int i = 0; i < rerankScores.size(); i++) {
                        childrenScores.put(childrenToRerank.get(i), rerankScores.get(i));
                    }

                } else {
                    tracker.updateProgress(taskId, 85, "Ошибка при выполнении реранкинга. Используем исходные оценки векторов...");
                }

            } else {
                tracker.updateProgress(taskId, 85, "Реранкинг пропущен. Используем исходные оценки векторов...");
            }

            // ЭТАП 3: Дедупликация и извлечение ID родителей (90%)
            tracker.updateProgress(taskId, 90, "Дедупликация и извлечение ID родителей (90%)...");

            boolean truncateByReankResults = !childrenScores.isEmpty();

            if (childrenScores.isEmpty()) {
                for(Document document: sortedChildren) {
                    childrenScores.put(document, document.getScore());
                }
            }

            Map<String, Double> parentMaxScores = new HashMap<>();

            for (Map.Entry<Document, Double> entry : childrenScores.entrySet()) {
                String parentId = (String)entry.getKey().getMetadata().get("parent_id"); // Получаем ID родителя
                Double score = entry.getValue();                // Получаем скор чайлда

                // merge делает следующее:
                // 1. Если parentId еще нет в мапе, он добавляет его со значением score.
                // 2. Если parentId уже есть, он применяет функцию Math.max к старому и новому значению.
                parentMaxScores.merge(parentId, score, Math::max);
            }

            if (truncateByReankResults) {

                parentMaxScores = parentMaxScores.entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .limit(topNrerankingResults)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (e1, e2) -> e1,
                                LinkedHashMap::new
                        ));
            }

            // ЭТАП 4: Пакетная загрузка родителей из Qdrant (95%)
            tracker.updateProgress(taskId, 95, "Загрузка полного контекста родителей...");

            List<Document> parents = fetchParentsByIds(parentMaxScores.keySet().stream().toList());

            Map<String, Double> parentMaxScoresFinal = parentMaxScores;

            // Собираем финальные результаты, используя текст родителя, но оценку (score) лучшего ребенка
            List<SearchService.SearchResult> initialResults = parents.stream()
                    .map(document -> {

                        if (document == null) return null;

                        return new SearchService.SearchResult(
                                document.getId(),
                                document.getText(), // 🔑 БЕРЕМ ТЕКСТ ИЗ РОДИТЕЛЯ
                                parentMaxScoresFinal.get(document.getId()), // 🔑 БЕРЕМ ОЦЕНКУ ИЗ РЕБЕНКА
                                document.getMetadata()
                        );
                    })
                    .filter(Objects::nonNull)
                    .toList();

            List<SearchService.SearchResult> finalResults = initialResults.stream()
                    .sorted(Comparator.comparing(SearchService.SearchResult::score).reversed())
                    .limit(topK)
                    .toList();

            tracker.updateProgress(taskId, 99, "Формирование ответа...");
            tracker.completeTask(taskId, finalResults);
            log.info("✅ Async search completed for taskId: {} , count of results {}", taskId, finalResults.size());

        } catch (Exception e) {
            log.error("❌ Async search failed for taskId: {}", taskId, e);
            tracker.failTask(taskId, "Ошибка при поиске: " + e.getMessage());
        }
    }

    // 🔑 МЕТОД ПАКЕТНОЙ ЗАГРУЗКИ РОДИТЕЛЕЙ
    /**
     * Пакетная загрузка родительских документов по их ID из Qdrant
     */
    private List<Document> fetchParentsByIds(List<String> parentIds) {
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