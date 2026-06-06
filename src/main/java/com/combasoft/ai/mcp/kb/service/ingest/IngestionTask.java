package com.combasoft.ai.mcp.kb.service.ingest;

import java.time.Instant;

public record IngestionTask(
        String taskId,
        String source,
        IngestStatus status,
        int progress,
        int totalChunks,
        int processedChunks,
        String message,
        Instant createdAt
) {

    /**
     * Компактный конструктор для создания новой задачи.
     */
    public IngestionTask(String taskId, String source) {
        this(taskId, source, IngestStatus.QUEUED, 0, 0, 0, "", Instant.now());
    }

    // --- Методы-хелперы для создания обновленных копий записи ---

    public IngestionTask started() {
        return new IngestionTask(taskId, source, IngestStatus.PARSING, progress, totalChunks, processedChunks, "Started", createdAt);
    }

    public IngestionTask setTotalChunks(int total) {
        return new IngestionTask(taskId, source, IngestStatus.CHUNKING, progress, total, processedChunks, message, createdAt);
    }

    public IngestionTask updateProgress(int processed) {
        int newProgress = totalChunks > 0 ? Math.min(99, (int) ((double) processed / totalChunks * 100)) : 0;
        return new IngestionTask(taskId, source, IngestStatus.EMBEDDING, newProgress, totalChunks, processed, message, createdAt);
    }

    public IngestionTask completed() {
        return new IngestionTask(taskId, source, IngestStatus.COMPLETED, 100, totalChunks, processedChunks, "Successfully indexed", createdAt);
    }

    public IngestionTask failed(String reason) {
        return new IngestionTask(taskId, source, IngestStatus.FAILED, progress, totalChunks, processedChunks, "Error: " + reason, createdAt);
    }
}
