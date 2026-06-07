package com.combasoft.ai.mcp.kb.service.ingest;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

@Component
public class IngestionProgressTracker {

    // Хранилище задач
    private final Map<String, IngestionTask> tasks = new ConcurrentHashMap<>();

    /**
     * Создает новую задачу и кладет её в мапу.
     */
    public IngestionTask createTask(String source) {
        IngestionTask task = new IngestionTask(UUID.randomUUID().toString(), source);
        tasks.put(task.taskId(), task);
        return task;
    }

    /**
     * Обновляет задачу атомарно.
     * Принимает функцию, которая берет текущую задачу и возвращает новую (обновленную).
     */
    public void updateTask(String taskId, UnaryOperator<IngestionTask> updater) {
        tasks.computeIfPresent(taskId, (id, currentTask) -> updater.apply(currentTask));
    }

    /**
     * Получает текущее состояние задачи.
     */
    public IngestionTask getTask(String taskId) {
        return tasks.get(taskId);
    }
}
