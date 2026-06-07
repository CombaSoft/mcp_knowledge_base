package com.combasoft.ai.mcp.kb.service;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SearchProgressTracker {

    private final Map<String, SearchTask> tasks = new ConcurrentHashMap<>();

    public String createTask(String query) {
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, new SearchTask(taskId, query,
                "QUEUED", 0, "Запрос поставлен в очередь", List.of()));
        return taskId;
    }

    public void updateProgress(String taskId, int progress, String message) {
        tasks.computeIfPresent(taskId, (id, task) ->
                new SearchTask(task.id, task.query, "PROCESSING", progress, message, task.results));
    }

    public void completeTask(String taskId, List<SearchService.SearchResult> results) {
        tasks.computeIfPresent(taskId, (id, task) ->
                new SearchTask(task.id, task.query, "COMPLETED", 100, "Поиск завершен", results));
    }

    public void failTask(String taskId, String errorMessage) {
        tasks.computeIfPresent(taskId, (id, task) ->
                new SearchTask(task.id, task.query, "FAILED", 0, errorMessage, task.results));
    }

    public SearchTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    // Immutable record для хранения состояния задачи
    public record SearchTask(
            String id,
            String query,
            String status, // QUEUED, PROCESSING, COMPLETED, FAILED
            int progress,  // 0-100
            String message,
            List<SearchService.SearchResult> results
    ) {}
}