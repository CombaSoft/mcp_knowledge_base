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
import java.util.regex.Pattern;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.!?]\\s+|\\n+");

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

    /**
     * Полный синхронный процесс (для простых вызовов).
     */
    public void ingestSingleFileSync(String sourcePath, Map<String, Object> metadata) throws Exception {
        Path path = Path.of(sourcePath);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Not a file: " + path);

        List<Document> documents = prepareDocuments(path, metadata);
        storeDocuments(documents);
        log.info("✅ Sync indexed: {} ({} chunks)", path.getFileName(), documents.size());
    }

    /**
     * Подготовка документов: Парсинг -> Чанкинг.
     * Вызывается из AsyncIngestService.
     */
    public List<Document> prepareDocuments(Path filePath, Map<String, Object> baseMetadata) throws Exception {

        // 🔒 Проверка размера файла перед чтением
        long sizeInBytes = Files.size(filePath);
        long limitInBytes = (long) kbConfig.getMaxFileSizeMb() * 1024 * 1024;

        if (sizeInBytes > limitInBytes) {
            throw new IllegalArgumentException(
                    String.format("File is too large: %.2f MB (limit: %d MB)",
                            sizeInBytes / (1024.0 * 1024.0), kbConfig.getMaxFileSizeMb())
            );
        }

        String content = parserFactory.getParser(filePath).extractText(filePath);
        if (content == null || content.isBlank()) return List.of();

        List<String> chunks = smartChunk(content, kbConfig.getChunkSize(), kbConfig.getChunkOverlap());
        List<Document> documents = new ArrayList<>();

        Map<String, Object> meta = new HashMap<>(baseMetadata);
        meta.put("source", filePath.toAbsolutePath().toString());
        meta.put("file_type", getFileExtension(filePath));
        meta.put("ingested_at", System.currentTimeMillis());

        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunkMeta = new HashMap<>(meta);
            chunkMeta.put("chunk_index", i);
            chunkMeta.put("total_chunks", chunks.size());
            documents.add(new Document(UUID.randomUUID().toString(), chunks.get(i), chunkMeta));
        }
        return documents;
    }

    /**
     * Сохранение батча документов в векторное хранилище.
     */
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

    private List<String> smartChunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = SENTENCE_BOUNDARY.split(text);
        StringBuilder current = new StringBuilder();
        int currentLen = 0;

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) continue;
            int len = trimmed.length() + 1;

            if (currentLen + len > chunkSize && currentLen > 0) {
                chunks.add(current.toString().trim());
                if (overlap > 0 && current.length() > overlap) {
                    current = new StringBuilder(current.substring(current.length() - overlap));
                    currentLen = current.length();
                } else {
                    current = new StringBuilder();
                    currentLen = 0;
                }
            }
            current.append(trimmed).append(" ");
            currentLen += len;
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks;
    }
}
