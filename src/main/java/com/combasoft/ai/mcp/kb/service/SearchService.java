package com.combasoft.ai.mcp.kb.service;



import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.vector.KbVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {
    private final VectorStore vectorStore;
    private final KbConfig kbConfig;

    public SearchService(@Qualifier("kbVectorStore") VectorStore vectorStore, KbConfig kbConfig) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
    }

    public List<SearchResult> search(String query, int limit, Map<String, Object> filters) {

        String filterExpr = "";

        if (filters != null && !filters.isEmpty()) {
            filterExpr = filters.entrySet().stream()
                    .map(e -> String.format("metadata['%s'] == '%s'", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(" AND "));
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

        return vectorStore.similaritySearch(request).stream()
                .map(doc -> new SearchResult(doc.getId(), doc.getText(), doc.getScore(), doc.getMetadata()))
                .sorted(Comparator.comparing(SearchResult::score).reversed())
                .toList();
    }

    public record SearchResult(String id, String content, double score, Map<String, Object> metadata) {}
}
