package com.combasoft.ai.mcp.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "kb")
public class KbConfig {

    private int parentChunkSize = 4096;
    private int childChunkSize = 512;
    private int chunkSize = 512;
    private int chunkOverlap = 50;
    private double vectorSimilarityThreshold = 0.45;
    private int rerankTopNResults = 3;
    private int maxSearchResults = 128;
    private int retrievalMultiplier = 10;
    private List<String> allowedSourceTypes = List.of("file", "web", "manual");
    private int maxFileSizeMb = 50;

    // --- Reranker Config ---
    // Инициализируем record значениями по умолчанию
    private RerankerProperties reranker = new RerankerProperties(true, "qwen3-reranker-0.6b", 20);

    public RerankerProperties getReranker() {
        return reranker;
    }

    public void setReranker(RerankerProperties reranker) {
        this.reranker = reranker;
    }

    // 🔑 Вложенный класс теперь является record'ом.
    // Spring Boot 3 автоматически свяжет YAML-поля с компонентами record'а.
    public record RerankerProperties(
            boolean enabled,
            String model,
            int maxDocsToRerank
    ) {}


    public int getParentChunkSize() { return parentChunkSize; }
    public void setParentChunkSize(int parentChunkSize) { this.parentChunkSize = parentChunkSize; }
    public int getChildChunkSize() { return childChunkSize; }
    public void setChildChunkSize(int childChunkSize) { this.childChunkSize = childChunkSize; }
    public int getRetrievalMultiplier() { return retrievalMultiplier; }
    public void setRetrievalMultiplier(int retrievalMultiplier) { this.retrievalMultiplier = retrievalMultiplier; }

    // Остальные старые геттеры/сеттеры...
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public double getVectorSimilarityThreshold() { return vectorSimilarityThreshold; }
    public void setVectorSimilarityThreshold(double vectorSimilarityThreshold) { this.vectorSimilarityThreshold = vectorSimilarityThreshold; }
    public int getMaxSearchResults() { return maxSearchResults; }
    public void setMaxSearchResults(int maxSearchResults) { this.maxSearchResults = maxSearchResults; }
    public List<String> getAllowedSourceTypes() { return allowedSourceTypes; }
    public void setAllowedSourceTypes(List<String> allowedSourceTypes) { this.allowedSourceTypes = allowedSourceTypes; }
    public int getMaxFileSizeMb() { return maxFileSizeMb; }
    public void setMaxFileSizeMb(int maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }
    public int getRerankTopNResults() { return rerankTopNResults; }
    public void setRerankTopNResults(int rerankTopNResults) { this.rerankTopNResults = rerankTopNResults; }
}
