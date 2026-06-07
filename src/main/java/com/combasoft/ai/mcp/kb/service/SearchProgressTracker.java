package com.combasoft.ai.mcp.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SearchProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(SearchProgressTracker.class);
    private final Map<String, SearchTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong taskCounter = new AtomicLong(0);

    public String createTask(String query, String document) {
        String taskId = "task_" + taskCounter.incrementAndGet();
        String displayQuery = (document != null && !document.isBlank())
                ? query + " [Doc: " + document + "]"
                : query;

        tasks.put(taskId, new SearchTask(taskId, displayQuery, document,
                "QUEUED", 0, "Запрос поставлен в очередь", List.of()));
        log.info("✅ Created new search task: {} for document: {}", taskId, document != null ? document : "ALL");
        return taskId;
    }

    public void updateProgress(String taskId, int progress, String message) {
        tasks.computeIfPresent(taskId, (id, task) ->
                new SearchTask(task.id, task.query, task.document,"PROCESSING", progress, message, task.results));
    }

    public void completeTask(String taskId, List<SearchService.SearchResult> results) {
        tasks.computeIfPresent(taskId, (id, task) ->
                new SearchTask(task.id, task.query, task.document,"COMPLETED", 100, "Поиск завершен", results));
    }

    public void failTask(String taskId, String errorMessage) {
        tasks.computeIfPresent(taskId, (id, task) ->
                new SearchTask(task.id, task.query, task.document,"FAILED", 0, errorMessage, task.results));
    }

    public SearchTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    // Immutable record для хранения состояния задачи
    public record SearchTask(
            String id,
            String query,
            String document, // Может быть null для глобального поиска
            String status,
            int progress,
            String message,
            List<SearchService.SearchResult> results
    ) {}
}