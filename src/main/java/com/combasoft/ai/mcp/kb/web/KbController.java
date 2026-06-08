package com.combasoft.ai.mcp.kb.web;


import com.combasoft.ai.mcp.kb.service.ingest.AsyncIngestService;
import com.combasoft.ai.mcp.kb.service.search.SearchService;
import com.combasoft.ai.mcp.kb.service.ingest.IngestionProgressTracker;
import com.combasoft.ai.mcp.kb.service.ingest.IngestionTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class KbController {

    private final SearchService searchService;
    private final AsyncIngestService asyncIngestService; // Для асинхронных задач
    private final IngestionProgressTracker tracker;

    public KbController(AsyncIngestService asyncIngestService,
                        SearchService searchService,
                        IngestionProgressTracker tracker) {
        this.asyncIngestService = asyncIngestService;
        this.searchService = searchService;
        this.tracker = tracker;
    }

    /**
     * 🚀 Запуск асинхронной индексации файла.
     * Возвращает taskId для отслеживания прогресса.
     */
    @PostMapping("/ingest/async/file")
    public ResponseEntity<Map<String, String>> ingestFile(@RequestParam("path") String path) {
        try {
            if (path == null || path.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Path is required"));
            }
            // 🔑 Заменяем ingestDocumentAsync на startIngestion
            String taskId = asyncIngestService.startIngestion(path, Map.of("ingested_by", "web_api"));
            return ResponseEntity.accepted()
                    .body(Map.of("status", "queued", "taskId", taskId, "message", "Ingestion started"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 📊 Получение статуса задачи индексации.
     */
    @GetMapping("/ingest/async/status/{taskId}")
    public ResponseEntity<IngestionTask> getTaskStatus(@PathVariable String taskId) {
        IngestionTask task = tracker.getTask(taskId);
        return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
    }

    /**
     * 🔍 Поиск по базе знаний (общий).
     */
    @PostMapping("/search")
    public ResponseEntity<List<SearchService.SearchResult>> search(@RequestBody SearchRequestDto request) {
        return ResponseEntity.ok(searchService.search(request.query(), request.limit(), null, request.filters()));
    }

    /**
     * 🔍 Поиск внутри конкретного документа.
     */
    @PostMapping("/search/doc")
    public ResponseEntity<List<SearchService.SearchResult>> searchInDoc(@RequestBody SearchRequestDto request) {
        if (request.sourcePath() == null) {
            return ResponseEntity.badRequest().body(null);
        }
        // Преобразуем путь в абсолютный для точного совпадения
        String absoluteSource = Path.of(request.sourcePath()).toAbsolutePath().toString();
        return ResponseEntity.ok(searchService.search(request.query(), request.limit(), absoluteSource, request.filters()));
    }

    /**
     * 🗑 Удаление документа из базы знаний.
     */
    @DeleteMapping("/documents")
    public ResponseEntity<Map<String, String>> deleteDocument(@RequestParam String path) {
        try {
            asyncIngestService.deleteDocument(path);
            return ResponseEntity.ok(Map.of("status", "deleted", "message", "Document removed"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 🔄 Обновление документа (удаление + загрузка).
     */
    @PostMapping("/ingest/async/update")
    public ResponseEntity<Map<String, String>> updateDocument(@RequestParam String path) {
        asyncIngestService.updateDocument(path, Map.of("ingested_by", "web_api"));
        return ResponseEntity.accepted().body(Map.of("status", "updating", "message", "Update task started"));
    }

    public record SearchRequestDto(String query, int limit, String sourcePath, Map<String, Object> filters) {}
    public record IngestRequest(String source, String sourceType, String content, Map<String, Object> metadata) {}

}
