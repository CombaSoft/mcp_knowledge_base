package com.combasoft.ai.mcp.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "kb")
public class KbConfig {

    private int chunkSize = 512;
    private int chunkOverlap = 50;
    private double similarityThreshold = 0.75;
    private int maxSearchResults = 10;
    private List<String> allowedSourceTypes = List.of("file", "web", "manual");

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public int getMaxSearchResults() { return maxSearchResults; }
    public void setMaxSearchResults(int maxSearchResults) { this.maxSearchResults = maxSearchResults; }
    public List<String> getAllowedSourceTypes() { return allowedSourceTypes; }
    public void setAllowedSourceTypes(List<String> allowedSourceTypes) { this.allowedSourceTypes = allowedSourceTypes; }
}
