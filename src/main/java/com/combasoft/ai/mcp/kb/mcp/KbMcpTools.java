package com.combasoft.ai.mcp.kb.mcp;


import com.combasoft.ai.mcp.kb.parser.DocumentParserFactory;
import com.combasoft.ai.mcp.kb.service.ingest.AsyncIngestService;
import com.combasoft.ai.mcp.kb.service.ingest.IngestionProgressTracker;
import com.combasoft.ai.mcp.kb.service.ingest.IngestionTask;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import com.combasoft.ai.mcp.kb.service.SearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KbMcpTools {

    private final SearchService searchService;
    private final AsyncIngestService asyncIngestService;
    private final IngestionProgressTracker tracker;
    private final String supportedFormatsHint;

    public KbMcpTools(AsyncIngestService asyncIngestService,
                      DocumentParserFactory parserFactory, SearchService searchService,
                      IngestionProgressTracker tracker) {
        this.asyncIngestService = asyncIngestService;
        this.searchService = searchService;
        this.tracker = tracker;
        this.supportedFormatsHint = String.join(", ", parserFactory.getAllSupportedExtensions());
    }

    @McpTool(description = "Indexes all supported files from a directory into the knowledge base. " +
            "Automatically skips unsupported formats. " +
            "Returns a taskId. Call getTaskStatus to track progress.")
    public String ingestDirectory(@McpToolParam(description = "Absolute path to directory") String path) {
        try {
            // Для MCP пока используем асинхронный метод, но можно добавить синхронный, если агенту нужно ждать
            // В текущей архитектуре asyncIngestService.ingestDocumentAsync ожидает файл.
            // Для директории нужен метод ingestFromPathAsync (см. ниже примечание).
            // Если метода для директории в AsyncIngestService нет, вызываем для файла:
            throw new IllegalArgumentException("Please use ingestFile for specific files for now, or implement directory async ingestion.");
        } catch (Exception e) {
            return "❌ Failed: " + e.getMessage();
        }
    }

    // 🔧 Временный инструмент для файла, пока не реализован асинхронный обход директорий
    @McpTool(description = "Indexes a single file.")
    public String ingestFileAsync(@McpToolParam(description = "Absolute path to a single file") String path) {
        try {
            String taskId = asyncIngestService.ingestDocumentAsync(path, Map.of("ingested_by", "mcp_agent"));
            return "✅ Task started. TaskId: " + taskId + ". Use getTaskStatus to check progress.";
        } catch (Exception e) {
            return "❌ Failed: " + e.getMessage();
        }
    }

    @McpTool(description = "Checks status of an ingestion task. Returns progress % and status (QUEUED, COMPLETED, FAILED).")
    public String getTaskStatus(@McpToolParam(description = "Task ID received from ingest tool") String taskId) {
        IngestionTask task = tracker.getTask(taskId);
        if (task == null) return "Task not found: " + taskId;

        return task.progress() + "% | " + task.status() + " | " + task.message();
    }

    @McpTool(description = "Searches the ENTIRE knowledge base for relevant information.")
    public String searchKnowledge(@McpToolParam(description = "Search query") String query,
                                  @McpToolParam(description = "Max results (default 5)") int limit) {
        return formatSearchResults(searchService.search(query, limit, null, Map.of()));
    }

    @McpTool(description = "Searches ONLY within a specific document (source path).")
    public String searchInDocument(
            @McpToolParam(description = "Search query") String query,
            @McpToolParam(description = "Absolute path to the source document") String sourcePath,
            @McpToolParam(description = "Max results (default 5)") int limit) {
        try {
            String absolutePath = java.nio.file.Path.of(sourcePath).toAbsolutePath().toString();
            return formatSearchResults(searchService.search(query, limit, absolutePath, Map.of()));
        } catch (Exception e) {
            return "❌ Error resolving path: " + e.getMessage();
        }
    }

    @McpTool(description = "Deletes all indexed chunks for a specific file from the knowledge base.")
    public String deleteDocument(@McpToolParam(description = "Absolute path to file to delete") String path) {
        try {
            asyncIngestService.deleteDocument(path);
            return "✅ Document successfully removed.";
        } catch (Exception e) {
            return "❌ Delete failed: " + e.getMessage();
        }
    }

    @McpTool(description = "Lists all supported file formats.")
    public String listSupportedFormats() {
        return supportedFormatsHint;
    }

    /**
     * Хелпер для форматирования ответа модели.
     */
    private String formatSearchResults(List<SearchService.SearchResult> results) {
        if (results.isEmpty()) {
            return "📭 No relevant information found.";
        }
        StringBuilder sb = new StringBuilder("📚 Found relevant context:\n\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("📄 [").append(i+1).append("] Source: ").append(r.metadata().get("source")).append("\n");
            sb.append("🔢 Score: ").append(String.format("%.3f", r.score())).append("\n");
            sb.append("📝 Content: ").append(r.content()).append("\n\n");
        }
        return sb.toString();
    }
}
