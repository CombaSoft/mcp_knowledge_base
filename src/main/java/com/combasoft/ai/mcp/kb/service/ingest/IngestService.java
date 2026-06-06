package com.combasoft.ai.mcp.kb.service.ingest;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.parser.DocumentParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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

    private final VectorStore vectorStore;
    private final KbConfig kbConfig;
    private final DocumentParserFactory parserFactory;

    public IngestService(@Qualifier("kbVectorStore") VectorStore vectorStore,
                         KbConfig kbConfig,
                         DocumentParserFactory parserFactory) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
        this.parserFactory = parserFactory;
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

        Document rawDoc = new Document(content);

        // 1. Создаем Parent chunks (большие куски для контекста LLM)
        // Параметры: chunkSize, minChunkSizeChars, maxNumChunks, minChunkLengthChars, keepSeparator
        TokenTextSplitter parentSplitter = new TokenTextSplitter(
                kbConfig.getParentChunkSize(), 100, 10000,
                5, true, DEFAULT_PUNCTUATION_MARKS
        );
        List<Document> parents = parentSplitter.apply(List.of(rawDoc));

        // 2. Создаем Child chunks (маленькие куски для точного векторного поиска)
        TokenTextSplitter childSplitter = new TokenTextSplitter(
                kbConfig.getChildChunkSize(), 50, 10000,
                5, true, DEFAULT_PUNCTUATION_MARKS
        );

        List<Document> allDocuments = new ArrayList<>();
        Map<String, Object> meta = new HashMap<>(baseMetadata);
        meta.put("source", filePath.toAbsolutePath().toString());
        meta.put("file_type", getFileExtension(filePath));
        meta.put("ingested_at", System.currentTimeMillis());

        for (Document parent : parents) {
            String parentId = UUID.randomUUID().toString();
            String parentText = parent.getText();

            // Разбиваем родителя на детей
            List<Document> children = childSplitter.apply(List.of(parent));

            for (Document child : children) {
                Map<String, Object> childMeta = new HashMap<>(meta);

                // 🔑 КЛЮЧЕВЫЕ ПОЛЯ ДЛЯ ПЛАНА Б:
                childMeta.put("parent_id", parentId);
                childMeta.put("is_parent", false); // Флаг, что это ребенок

                // Сохраняем полный текст родителя прямо в метаданные ребенка!
                // Это избавляет от необходимости делать второй запрос к Qdrant.
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