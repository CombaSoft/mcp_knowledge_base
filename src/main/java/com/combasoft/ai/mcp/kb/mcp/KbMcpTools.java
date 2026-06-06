package com.combasoft.ai.mcp.kb.mcp;


import com.combasoft.ai.mcp.kb.parser.DocumentParserFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import com.combasoft.ai.mcp.kb.service.IngestService;
import com.combasoft.ai.mcp.kb.service.SearchService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class KbMcpTools {

    private final IngestService ingestService;
    private final DocumentParserFactory parserFactory;
    private final SearchService searchService;

    public KbMcpTools(IngestService ingestService, DocumentParserFactory parserFactory, SearchService searchService) {
        this.ingestService = ingestService;
        this.parserFactory = parserFactory;
        this.searchService = searchService;
    }

    @McpTool(description = "Indexes all supported files from a directory into the knowledge base. " +
            "Automatically skips unsupported formats. " +
            "Waits until indexing finishes.")
    public String ingestDirectory(@McpToolParam(description = "Absolute path to directory") String path) {
        try {
            ingestService.ingestFromPathSync(path, Map.of("ingested_by", "mcp_agent"));
            return "✅ Directory successfully indexed. You can now call searchKnowledge.";
        } catch (Exception e) {
            return "❌ Failed to index directory: " + e.getMessage();
        }
    }

    @McpTool(description = "Indexes a single file into the knowledge base. " +
            "Waits until indexing finishes.")
    public String ingestOneFile(@McpToolParam(description = "Absolute path to a single file") String path) {
        try {
            ingestService.ingestSingleFileSync(path, Map.of("ingested_by", "mcp_agent"));
            return "✅ File successfully indexed: " + Path.of(path).getFileName() + ". You can now call searchKnowledge.";
        } catch (IllegalArgumentException e) {
            return "❌ Invalid path or format: " + e.getMessage();
        } catch (Exception e) {
            return "❌ Failed to index file: " + e.getMessage();
        }
    }

    @McpTool(description = "Lists all supported files formats")
    public String listSupportedFormats() {
        return String.join(", ", parserFactory.getAllSupportedExtensions());
    }

    @McpTool(description = "Searches the knowledge base for relevant information based on a query.")
    public String searchKnowledge(
            @McpToolParam(description = "Search query") String query,
            @McpToolParam(description = "Max results (default 5)") int limit) {
        try {
            List<SearchService.SearchResult> results = searchService.search(query, limit <= 0 ? 5 : limit, Map.of());
            if (results.isEmpty()) {
                return "📭 No relevant information found. Try ingesting documents first with ingestDirectory or ingestOneFile.";
            }

            StringBuilder sb = new StringBuilder("📚 Found relevant context:\n\n");
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i);
                sb.append("📄 [").append(i+1).append("] Source: ").append(r.metadata().get("source")).append("\n");
                sb.append("🔢 Score: ").append(String.format("%.3f", r.score())).append("\n");
                sb.append("📝 Content: ").append(r.content()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ Search failed: " + e.getMessage();
        }
    }
}
