package com.combasoft.ai.mcp.kb.service.ingest;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.parser.DocumentParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final List<Character> DEFAULT_PUNCTUATION_MARKS = List.of('.', '?', '!', '\n');

    // Безопасный лимит в символах (примерно 750-1000 токенов, безопасно для любой модели эмбеддингов)
    private static final int MAX_SAFE_CHAR_LENGTH = 3000;

    private final VectorStore vectorStore;
    private final KbConfig kbConfig;
    private final DocumentParserFactory parserFactory;
    private final CustomTextSplitter customSplitter;

    public IngestService(@Qualifier("kbVectorStore") VectorStore vectorStore,
                         KbConfig kbConfig,
                         DocumentParserFactory parserFactory,
                         CustomTextSplitter customSplitter) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
        this.parserFactory = parserFactory;
        this.customSplitter = customSplitter;
    }

    public void ingestSingleFileSync(String sourcePath, Map<String, Object> metadata) throws Exception {
        Path path = Path.of(sourcePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Not a file: " + path);
        }

        List<Document> documents = prepareDocuments(path, metadata);
        storeDocuments(documents);
        log.info("✅ Sync indexed: {} ({} total child chunks created)", path.getFileName(), documents.size());
    }

    public List<Document> prepareDocuments(Path filePath, Map<String, Object> baseMetadata) throws Exception {
        long sizeInBytes = Files.size(filePath);
        long limitInBytes = (long) kbConfig.getMaxFileSizeMb() * 1024 * 1024;

        if (sizeInBytes > limitInBytes) {
            throw new IllegalArgumentException(
                    String.format("File is too large: %.2f MB (limit: %d MB)",
                            sizeInBytes / (1024.0 * 1024.0), kbConfig.getMaxFileSizeMb())
            );
        }

        String content = parserFactory.getParser(filePath).extractText(filePath);
        if (content == null || content.isBlank()) {
            return List.of();
        }

        Map<String, Object> meta = new HashMap<>(baseMetadata);
        meta.put("source", filePath.toAbsolutePath().toString());
        meta.put("file_type", getFileExtension(filePath));
        meta.put("ingested_at", System.currentTimeMillis());

        List<Document> allDocuments = new ArrayList<>();

        // 🔑 Используем наш надежный кастомный сплиттер
        // Parent: ~2000 символов (безопасно ~500-600 токенов), overlap 200
        List<Document> parents = customSplitter.splitMy(content, kbConfig.getParentChunkSize(), 200, meta);

        for (Document parent : parents) {
            String parentId = parent.getId();
            String parentText = parent.getText();

            // Child: ~500 символов (безопасно ~120-150 токенов), overlap 50
            List<Document> children = customSplitter.splitMy(parentText, kbConfig.getChildChunkSize(), 50, meta);

            for (Document child : children) {
                // Внутри цикла создания children в IngestService.java
                Map<String, Object> childMeta = new HashMap<>(meta);
                childMeta.put("parent_id", parentId);

                // 🔑 ИЗМЕНЕНИЕ: Используем строку "false" вместо булевого false
                childMeta.put("is_parent", "false");

                childMeta.put("parent_text", parentText);
                childMeta.put("chunk_index", allDocuments.size());

                allDocuments.add(new Document(UUID.randomUUID().toString(), child.getText(), childMeta));
            }
        }

        return allDocuments;
    }

    public void storeDocuments(List<Document> documents) {
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
    }

    private String getFileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(dot + 1).toLowerCase() : "unknown";
    }
}