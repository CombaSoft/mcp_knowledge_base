package com.combasoft.ai.mcp.kb.web;


import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.parser.DocumentParserFactory;
import com.combasoft.ai.mcp.kb.service.IngestService;
import com.combasoft.ai.mcp.kb.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class KbController {
    private final IngestService ingestService;
    private final SearchService searchService;
    private final KbConfig kbConfig;
    private final DocumentParserFactory parserFactory;

    public KbController(IngestService ingestService, SearchService searchService, KbConfig kbConfig, DocumentParserFactory parserFactory) {
        this.ingestService = ingestService;
        this.searchService = searchService;
        this.kbConfig = kbConfig;
        this.parserFactory = parserFactory;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest(@RequestBody IngestRequest request) {
        if (!kbConfig.getAllowedSourceTypes().contains(request.sourceType())) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Source type not allowed"));
        }
        ingestService.ingestRawContent(request.source(), request.content(), request.metadata());
        return ResponseEntity.accepted().body(Map.of("status", "queued", "message", "Processing started"));
    }

    @PostMapping("/ingest/path")
    public ResponseEntity<Map<String, String>> ingestFromPath(@RequestBody IngestPathRequest request) {
        ingestService.ingestFromPath(request.path(), request.metadata());
        return ResponseEntity.accepted().body(Map.of("status", "queued", "message", "Processing started"));
    }

    @PostMapping("/search")
    public ResponseEntity<List<SearchService.SearchResult>> search(@RequestBody SearchRequestDto request) {
        return ResponseEntity.ok(searchService.search(request.query(), request.limit(), request.filters()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "chunkSize", kbConfig.getChunkSize(),
                "similarityThreshold", kbConfig.getSimilarityThreshold(),
                "vectorStorePath", "./kb-data/vector-store.dat"
        ));
    }

    @GetMapping("/supportedFormats")
    public ResponseEntity<Map<String, Object>> supportedFormats() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "listOfSupportedFormats", String.join(", ", parserFactory.getAllSupportedExtensions())
        ));
    }

    @PostMapping("/ingest/file")
    public ResponseEntity<Map<String, String>> ingestFile(@RequestParam("path") String path) {
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Path parameter is required"));
        }
        try {
            ingestService.ingestSingleFileSync(path, Map.of("ingested_by", "web_debug"));
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "File successfully indexed: " + Path.of(path).getFileName()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    public record IngestRequest(String source, String sourceType, String content, Map<String, Object> metadata) {}
    public record IngestPathRequest(String path, Map<String, Object> metadata) {}
    public record SearchRequestDto(String query, int limit, Map<String, Object> filters) {}
}
