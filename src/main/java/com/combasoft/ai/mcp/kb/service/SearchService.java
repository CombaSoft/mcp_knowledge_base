package com.combasoft.ai.mcp.kb.service;



import com.combasoft.ai.mcp.kb.config.KbConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;


@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final VectorStore vectorStore;
    private final KbConfig kbConfig;

    public SearchService(@Qualifier("kbVectorStore") VectorStore vectorStore, KbConfig kbConfig) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
    }
    /**
     * Выполняет семантический поиск с фильтрами.
     *
     * @param query         Поисковый запрос
     * @param limit         Максимальное количество результатов (0 = значение из конфига)
     * @param sourceFilter  Абсолютный путь к файлу (если нужно искать только внутри конкретного документа)
     * @param otherFilters  Дополнительные метаданные для фильтрации
     */
    public List<SearchResult> search(String query, int limit, String sourceFilter, Map<String, Object> otherFilters) {

        int topK = (limit > 0) ? limit : kbConfig.getMaxSearchResults();

        // 🛡️ Сборка выражения фильтра
        List<String> conditions = new ArrayList<>();

        // 1. Фильтр по исходному файлу (source)
        if (sourceFilter != null && !sourceFilter.isBlank()) {
            // Важно: путь должен быть экранирован или точен, если в Qdrant записан normalized path
            conditions.add(String.format("metadata['source'] == '%s'", sourceFilter));
        }

        // 2. Дополнительные фильтры
        if (otherFilters != null && !otherFilters.isEmpty()) {
            otherFilters.forEach((k, v) ->
                    conditions.add(String.format("metadata['%s'] == '%s'", k, v))
            );
        }

        String filterExpr = "";

        // 3. Применяем фильтр только если он не пустой (защита от "Expression should not be empty!")
        if (!conditions.isEmpty()) {
            filterExpr = String.join(" AND ", conditions);
            log.debug("🔍 Applying Qdrant filter: {}", filterExpr);
        }

        SearchRequest request;

        if(!filterExpr.isBlank()) {
            request = SearchRequest.builder()
                    .query(query)
                    .topK(limit > 0 ? limit : kbConfig.getMaxSearchResults())
                    .similarityThreshold(kbConfig.getSimilarityThreshold())
                    .filterExpression(filterExpr)
                    .build();
        } else {
            request = SearchRequest.builder()
                    .query(query)
                    .topK(limit > 0 ? limit : kbConfig.getMaxSearchResults())
                    .similarityThreshold(kbConfig.getSimilarityThreshold())
                    .build();
        }


        log.info("📡 Executing similaritySearch: query='{}', topK={}, filters={}",
                query, topK, conditions.isEmpty() ? "none" : conditions.size());

        // 4. Выполнение запроса
        List<Document> rawResults = vectorStore.similaritySearch(request);

        // 5. Логирование для отладки релевантности
        log.debug("🔍 Raw results count: {}", rawResults.size());

        return rawResults.stream()
                .map(doc -> new SearchResult(doc.getId(), doc.getText(), doc.getScore(), doc.getMetadata()))
                // Сортировка по убыванию релевантности (Qdrant обычно возвращает уже отсортированным, но подстрахуемся)
                .sorted(Comparator.comparing(SearchResult::score).reversed())
                .toList();
    }

    /**
     * Record для удобного возврата результатов.
     */
    public record SearchResult(
            String id,
            String content,
            double score,
            Map<String, Object> metadata
    ) {}
}
