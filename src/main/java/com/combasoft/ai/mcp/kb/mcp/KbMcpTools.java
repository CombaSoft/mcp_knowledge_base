package com.combasoft.ai.mcp.kb.mcp;


import com.combasoft.ai.mcp.kb.parser.DocumentParserFactory;
import com.combasoft.ai.mcp.kb.service.search.AsyncSearchService;
import com.combasoft.ai.mcp.kb.service.search.SearchProgressTracker;
import com.combasoft.ai.mcp.kb.service.ingest.AsyncIngestService;
import com.combasoft.ai.mcp.kb.service.ingest.IngestionProgressTracker;
import com.combasoft.ai.mcp.kb.service.ingest.IngestionTask;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import com.combasoft.ai.mcp.kb.service.search.SearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KbMcpTools {

    private final SearchService searchService;
    private final AsyncIngestService asyncIngestService;
    private final IngestionProgressTracker ingestionProgressTracker;
    private final String supportedFormatsHint;
    private final AsyncSearchService asyncSearchService;
    private final SearchProgressTracker searchProgressTracker;

    public KbMcpTools(AsyncIngestService asyncIngestService,
                      DocumentParserFactory parserFactory, SearchService searchService,
                      IngestionProgressTracker tracker,
                      AsyncSearchService asyncSearchService,
                      SearchProgressTracker searchProgressTracker) {
        this.asyncIngestService = asyncIngestService;
        this.searchService = searchService;
        this.ingestionProgressTracker = tracker;
        this.searchProgressTracker = searchProgressTracker;
        this.asyncSearchService = asyncSearchService;
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
            // 🔑 Заменяем ingestDocumentAsync на startIngestion
            String taskId = asyncIngestService.startIngestion(path, Map.of("ingested_by", "mcp_agent"));
            return "✅ Task started. TaskId: " + taskId + ". Use getTaskStatus to check progress.";
        } catch (Exception e) {
            return "❌ Failed: " + e.getMessage();
        }
    }

    @McpTool(description = "Checks status of an ingestion task. Returns progress % and " +
            "status (QUEUED, PARSING, CHUNKING, EMBEDDING,)." +
            "CRITICAL RULE: If the status is one of 'QUEUED', 'PARSING', 'CHUNKING', 'EMBEDDING', DO NOT call this tool again immediately. " +
            "Instead, reply to the user with: 'The file ingestion is still processing (X% done). I will wait for your command to check again.' " +
            "Only call this tool again when the user explicitly asks you to check the status.")
    public String getIngestTaskStatus(@McpToolParam(description = "Task ID received from ingest tool") String taskId) {
        IngestionTask task = ingestionProgressTracker.getTask(taskId);
        if (task == null) return "Task not found: " + taskId;

        return task.progress() + "% | " + task.status() + " | " + task.message();
    }

    @McpTool(name = "start_knowledge_search", description = "Starts an asynchronous search. Returns a short taskId. Use 'get_search_status' to check progress.")
    public String startKnowledgeSearch(
            @McpToolParam(description = "The user's search query") String query,
            @McpToolParam(description = "Maximum number of results (default 5)") Integer limit,
            @McpToolParam(description = "Use LLM reranking for higher accuracy. Set to FALSE for faster, CPU-friendly search (default: flase). If enabled, only 5 top results passed to result", required = false) Boolean useReranking) {

        int topK = (limit != null && limit > 0) ? limit : 5;
        // По умолчанию выключаем реранкинг, если агент явно не попросил обратного
        boolean rerank = (useReranking != null) && useReranking;

        String taskId = asyncSearchService.startSearch(query, topK, rerank);

        String rerankStatus = rerank ? "with LLM reranking (slower, higher accuracy)" : "without LLM reranking (fast, CPU-friendly)";
        return String.format("SEARCH_STARTED. TaskId: %s. Mode: %s. IMPORTANT: Copy this TaskId exactly and use 'get_search_status' to check progress.", taskId, rerankStatus);
    }

    @McpTool(name = "get_search_status", description = "Checks the progress of an asynchronous search task. Use this repeatedly until status is 'COMPLETED' or 'FAILED'.")
    public String getSearchStatus(
            @McpToolParam(description = "The taskId returned by start_knowledge_search") String taskId) {

        SearchProgressTracker.SearchTask task = searchProgressTracker.getTask(taskId);
        if (task == null) {
            return "Error: TaskId not found.";
        }

        if ("COMPLETED".equals(task.status()) || "FAILED".equals(task.status())) {
            return String.format("Status: %s. Progress: %d%%. Message: %s. (Now use 'get_search_results' to get the data)",
                    task.status(), task.progress(), task.message());
        }

        return String.format("Status: %s. Progress: %d%%. Message: %s. Please wait and check again.",
                task.status(), task.progress(), task.message());
    }

    @McpTool(name = "get_search_results", description = "Retrieves the final results of a COMPLETED search task. Only call this after get_search_status reports 'COMPLETED'.")
    public String getSearchResults(
            @McpToolParam(description = "The taskId of the completed search") String taskId) {

        SearchProgressTracker.SearchTask task = searchProgressTracker.getTask(taskId);
        if (task == null) {
            return "Error: TaskId not found.";
        }

        if (!"COMPLETED".equals(task.status())) {
            return "Error: Task is not completed yet. Current status: " + task.status() + ". Use get_search_status first.";
        }

        if (task.results() == null || task.results().isEmpty()) {
            return "Search completed, but no relevant information was found in the knowledge base.";
        }

        StringBuilder response = new StringBuilder("Found ").append(task.results().size()).append(" relevant fragments:\n\n");
        for (int i = 0; i < task.results().size(); i++) {
            SearchService.SearchResult r = task.results().get(i);
            response.append("--- Fragment ").append(i + 1).append(" (Relevance Score: ").append(String.format("%.2f", r.score())).append(") ---\n");
            response.append(r.content()).append("\n\n");
            if (r.metadata().containsKey("source")) {
                response.append("Source: ").append(r.metadata().get("source")).append("\n\n");
            }
        }
        return response.toString();
    }

    @McpTool(name = "start_document_search", description = "Starts an asynchronous search within a SPECIFIC document. Use this when the user asks about a particular file. Returns a short taskId.")
    public String startDocumentSearch(
            @McpToolParam(description = "The user's search query") String query,
            @McpToolParam(description = "Exact source path or filename of the document") String sourcePath,
            @McpToolParam(description = "Maximum number of results (default 5)", required = false) Integer limit,
            @McpToolParam(description = "Use LLM reranking. Set to FALSE for faster, CPU-friendly search (default: false)", required = false) Boolean useReranking) {

        int topK = (limit != null && limit > 0) ? limit : 5;
        // По умолчанию выключаем реранкинг, если агент явно не попросил обратного
        boolean rerank = (useReranking != null) && useReranking;

        String taskId = asyncSearchService.startSearchInDocument(query, sourcePath, topK, rerank);

        String rerankStatus = rerank ? "with LLM reranking" : "without LLM reranking (fast)";
        return String.format("DOC_SEARCH_STARTED. TaskId: %s. Target: %s. Mode: %s. IMPORTANT: Copy this TaskId exactly and use 'get_search_status' to check progress.",
                taskId, sourcePath, rerankStatus);
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
