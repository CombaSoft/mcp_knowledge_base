package com.combasoft.ai.mcp.kb.service;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.service.reranking.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    public AsyncSearchService(@Qualifier("kbVectorStore") VectorStore vectorStore,
                              KbConfig kbConfig,
                              Reranker reranker,
                              SearchProgressTracker tracker,
                              @Lazy AsyncSearchService asyncSearchService) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
        this.reranker = reranker;
        this.tracker = tracker;
        this.self = asyncSearchService;
    }

    /**
     * Точка входа: создает задачу и запускает асинхронное выполнение
     */
    public String startSearch(String query, int limit, boolean useReranking) {
        String taskId = tracker.createTask(query, null);
        self.executeSearchAsync(taskId, query, limit, useReranking);
        return taskId;
    }

    /**
     * Асинхронное выполнение поиска с отчетом о прогрессе
     */
    @Async
    public void executeSearchAsync(String taskId, String query, int limit, boolean useReranking) {
        try {
            int topK = (limit > 0) ? limit : kbConfig.getMaxSearchResults();
            int multiplier = kbConfig.getRetrievalMultiplier();
            int childTopK = topK * multiplier;

            // ЭТАП 1: Векторный поиск (20%)
            tracker.updateProgress(taskId, 20, "Выполняется векторный поиск...");
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(childTopK)
                    .similarityThreshold(kbConfig.getSimilarityThreshold())
                    .filterExpression("is_parent == 'false'")
                    .build();

            List<Document> children = vectorStore.similaritySearch(request);
            if (children.isEmpty()) {
                tracker.completeTask(taskId, List.of());
                return;
            }

            // ЭТАП 2: Дедупликация и извлечение родителей (50%)
            tracker.updateProgress(taskId, 50, "Объединение результатов и извлечение контекста...");
            Map<String, Document> uniqueParents = children.stream()
                    .collect(Collectors.toMap(
                            doc -> (String) doc.getMetadata().get("parent_id"),
                            doc -> doc,
                            (existing, replacement) -> existing,
                            LinkedHashMap::new
                    ));

            List<SearchService.SearchResult> initialResults = uniqueParents.values().stream()
                    .limit(topK)
                    .map(doc -> {
                        String parentText = (String) doc.getMetadata().getOrDefault("parent_text", doc.getText());
                        return new SearchService.SearchResult(
                                (String) doc.getMetadata().get("parent_id"),
                                parentText,
                                doc.getScore(), // Изначальный векторный скор
                                doc.getMetadata()
                        );
                    }).toList();

            List<SearchService.SearchResult> finalResults;

            // 🔑 ЭТАП 3: LLM Реранкинг
            if (useReranking) {
                tracker.updateProgress(taskId, 70, "Запуск LLM реранкинга (может занять время)...");
                List<String> parentTexts = initialResults.stream().map(SearchService.SearchResult::content).toList();
                List<Double> rerankScores = reranker.score(query, parentTexts);

                List<SearchService.SearchResult> rerankedResults = new ArrayList<>();
                for (int i = 0; i < initialResults.size(); i++) {
                    rerankedResults.add(new SearchService.SearchResult(
                            initialResults.get(i).id(),
                            initialResults.get(i).content(),
                            rerankScores.get(i), // Переопределяем скор на оценку LLM
                            initialResults.get(i).metadata()
                    ));
                }

                finalResults = rerankedResults.stream()
                        .sorted(Comparator.comparing(SearchService.SearchResult::score).reversed())
                        .toList();

                tracker.updateProgress(taskId, 95, "Реранкинг завершен, финализация...");
            } else {
                // 🔑 Альтернатива: просто сортируем по исходному векторному скору
                tracker.updateProgress(taskId, 70, "Реранкинг пропущен, сортировка по векторному соответствию...");
                finalResults = initialResults.stream()
                        .sorted(Comparator.comparing(SearchService.SearchResult::score).reversed())
                        .toList();
                tracker.updateProgress(taskId, 95, "Результаты готовы...");
            }

            // ЭТАП 4: Завершение (100%)
            tracker.completeTask(taskId, finalResults);
            log.info("✅ Async search completed for taskId: {} (Reranking: {})", taskId, useReranking);

        } catch (Exception e) {
            log.error("❌ Async search failed for taskId: {}", taskId, e);
            tracker.failTask(taskId, "Ошибка при поиске: " + e.getMessage());
        }
    }


    /**
     * Точка входа для поиска в КОНКРЕТНОМ документе
     */
    public String startSearchInDocument(String query, String sourcePath, int limit, boolean useReranking) {
        String taskId = tracker.createTask(query, sourcePath);
        executeDocumentSearchAsync(taskId, query, sourcePath, limit, useReranking);
        return taskId;
    }

    @Async
    public void executeDocumentSearchAsync(String taskId, String query, String sourcePath, int limit, boolean useReranking) {
        try {
            int topK = (limit > 0) ? limit : kbConfig.getMaxSearchResults();
            int multiplier = kbConfig.getRetrievalMultiplier();
            int childTopK = topK * multiplier;

            List<String> conditions = new ArrayList<>();
            conditions.add("is_parent == 'false'");
            conditions.add("source == '" + sourcePath + "'");
            String filterExpr = String.join(" AND ", conditions);

            tracker.updateProgress(taskId, 20, "Векторный поиск в документе...");
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

            tracker.updateProgress(taskId, 50, "Извлечение родительского контекста...");
            Map<String, Document> uniqueParents = children.stream()
                    .collect(Collectors.toMap(
                            doc -> (String) doc.getMetadata().get("parent_id"),
                            doc -> doc,
                            (existing, replacement) -> existing,
                            LinkedHashMap::new
                    ));

            List<SearchService.SearchResult> initialResults = uniqueParents.values().stream()
                    .limit(topK)
                    .map(doc -> {
                        String parentText = (String) doc.getMetadata().getOrDefault("parent_text", doc.getText());
                        return new SearchService.SearchResult(
                                (String) doc.getMetadata().get("parent_id"),
                                parentText,
                                doc.getScore(),
                                doc.getMetadata()
                        );
                    }).toList();

            List<SearchService.SearchResult> finalResults;

            if (useReranking) {
                tracker.updateProgress(taskId, 70, "Запуск LLM реранкинга...");
                List<String> parentTexts = initialResults.stream().map(SearchService.SearchResult::content).toList();
                List<Double> rerankScores = reranker.score(query, parentTexts);

                List<SearchService.SearchResult> rerankedResults = new ArrayList<>();
                for (int i = 0; i < initialResults.size(); i++) {
                    rerankedResults.add(new SearchService.SearchResult(
                            initialResults.get(i).id(),
                            initialResults.get(i).content(),
                            rerankScores.get(i),
                            initialResults.get(i).metadata()
                    ));
                }
                finalResults = rerankedResults.stream()
                        .sorted(Comparator.comparing(SearchService.SearchResult::score).reversed())
                        .toList();
            } else {
                tracker.updateProgress(taskId, 70, "Реранкинг пропущен, сортировка по векторам...");
                finalResults = initialResults.stream()
                        .sorted(Comparator.comparing(SearchService.SearchResult::score).reversed())
                        .toList();
            }

            tracker.updateProgress(taskId, 95, "Финализация...");
            tracker.completeTask(taskId, finalResults);
            log.info("✅ Async document search completed for taskId: {}", taskId);

        } catch (Exception e) {
            log.error("❌ Async document search failed for taskId: {}", taskId, e);
            tracker.failTask(taskId, "Ошибка при поиске в документе: " + e.getMessage());
        }
    }
}