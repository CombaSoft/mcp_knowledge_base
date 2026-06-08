package com.combasoft.ai.mcp.kb.service.search;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.service.search.reranking.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final VectorStore vectorStore;
    private final KbConfig kbConfig;
    private final Reranker reranker;

    public SearchService(@Qualifier("kbVectorStore") VectorStore vectorStore,
                         KbConfig kbConfig,
                         Reranker reranker) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
        this.reranker = reranker;
    }

    public List<SearchResult> search(String query, int limit, String sourceFilter, Map<String, Object> otherFilters) {
        int topK = (limit > 0) ? limit : kbConfig.getMaxSearchResults();
        int multiplier = kbConfig.getRetrievalMultiplier();
        int childTopK = topK * multiplier;

        List<String> conditions = new ArrayList<>();

        conditions.add("is_parent == 'false'");

        if (sourceFilter != null && !sourceFilter.isBlank()) {
            conditions.add("source == '" + sourceFilter + "'");
        }

        if (otherFilters != null && !otherFilters.isEmpty()) {
            otherFilters.forEach((k, v) ->
                    conditions.add(k + " == '" + v + "'")
            );
        }

        String filterExpr = String.join(" AND ", conditions);
        log.debug("🔍 Applying Qdrant filter: {}", filterExpr);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(childTopK)
                .similarityThreshold(kbConfig.getSimilarityThreshold())
                .filterExpression(filterExpr)
                .build();

        log.info("📡 Searching CHILDREN: query='{}', topK={}", query, childTopK);
        List<Document> children = vectorStore.similaritySearch(request);

        if (children.isEmpty()) {
            return List.of();
        }

        // Дедупликация по parent_id с сохранением порядка релевантности
        Map<String, Document> uniqueParentsMap = children.stream()
                .collect(Collectors.toMap(
                        doc -> (String) doc.getMetadata().get("parent_id"),
                        doc -> doc,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));

        // Берем топ-K уникальных родителей для реранкинга
        List<Document> topParents = uniqueParentsMap.values().stream()
                .limit(topK)
                .toList();

        List<SearchResult> initialResults = topParents.stream()
                .map(doc -> {
                    String parentText = (String) doc.getMetadata().get("parent_text");
                    String finalText = (parentText != null && !parentText.isBlank()) ? parentText : doc.getText();
                    return new SearchResult(
                            (String) doc.getMetadata().get("parent_id"),
                            finalText,
                            doc.getScore(),
                            doc.getMetadata()
                    );
                })
                .toList();

        if (initialResults.isEmpty()) {
            return List.of();
        }

        // 🔑 Применяем LLM Reranking
        List<String> parentTexts = initialResults.stream().map(SearchResult::content).toList();
        List<Double> rerankScores = reranker.score(query, parentTexts);

        List<SearchResult> rerankedResults = new ArrayList<>();
        for (int i = 0; i < initialResults.size(); i++) {
            rerankedResults.add(new SearchResult(
                    initialResults.get(i).id(),
                    initialResults.get(i).content(),
                    rerankScores.get(i), // Новая оценка от LLM
                    initialResults.get(i).metadata()
            ));
        }

        // Финальная сортировка по убыванию оценки LLM
        return rerankedResults.stream()
                .sorted(Comparator.comparing(SearchResult::score).reversed())
                .toList(); // topK уже применен выше, здесь просто сортируем
    }

    public record SearchResult(
            String id,
            String content,
            double score,
            Map<String, Object> metadata
    ) {}
}