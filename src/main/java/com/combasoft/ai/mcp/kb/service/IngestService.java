package com.combasoft.ai.mcp.kb.service;


import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.parser.DocumentParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
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


    public IngestService(@Qualifier("kbVectorStore") VectorStore vectorStore, KbConfig kbConfig, DocumentParserFactory parserFactory) {
        this.vectorStore = vectorStore;
        this.kbConfig = kbConfig;
        this.parserFactory = parserFactory;
    }

    @Async
    public void ingestFromPath(String sourcePath, Map<String, Object> metadata) {
        try {
            Path path = Path.of(sourcePath);
            if (Files.isDirectory(path)) {
                Files.walk(path)
                        .filter(Files::isRegularFile)
                        .forEach(file -> ingestFile(file, metadata));
            } else {
                ingestFile(path, metadata);
            }
        } catch (Exception e) {
            log.error("❌ Failed to ingest from path {}: {}", sourcePath, e.getMessage());
        }
    }

    @Async
    public void ingestRawContent(String source, String content, Map<String, Object> metadata) {
        if (content == null || content.isBlank()) return;
        try {
            processAndStoreChunks(source, List.of(content), metadata);
            log.info("✅ Ingested raw content from {}", source);
        } catch (Exception e) {
            log.error("❌ Failed to ingest raw content {}: {}", source, e.getMessage());
        }
    }

    private void ingestFile(Path filePath, Map<String, Object> baseMetadata) {
        try {
            String content = parserFactory.getParser(filePath).extractText(filePath);
            if (content == null || content.isBlank()) return;

            List<String> chunks = smartChunk(content, kbConfig.getChunkSize(), kbConfig.getChunkOverlap());

            Map<String, Object> meta = new HashMap<>(baseMetadata);
            meta.put("source", filePath.toAbsolutePath().toString());
            meta.put("file_type", getFileExtension(filePath));
            meta.put("ingested_at", System.currentTimeMillis());

            processAndStoreChunks(filePath.toString(), chunks, meta);
            log.info("✅ Ingested {} chunks from {} ({})", chunks.size(), filePath.getFileName(), getFileExtension(filePath));
        } catch (Exception e) {
            log.error("❌ Failed to ingest file {}: {}", filePath, e.getMessage());
        }
    }

    // 🔑 Синхронная версия для MCP-инструментов
    public void ingestFromPathSync(String sourcePath, Map<String, Object> metadata) throws Exception {
        Path path = Path.of(sourcePath);
        if (Files.isDirectory(path)) {
            Files.walk(path)
                    .filter(Files::isRegularFile)
                    .filter(parserFactory::isSupported) // ✅ Авто-фильтр по расширениям из парсеров
                    .forEach(file -> {
                        try { ingestFileSync(file, metadata); }
                        catch (Exception e) { log.error("Sync ingest error: {}", e.getMessage()); }
                    });
        } else {
            ingestFileSync(path, metadata);
        }
    }

    /**
     * Синхронная индексация ОДНОГО файла (без рекурсии по директории).
     * @throws IllegalArgumentException если путь не является файлом
     */
    public void ingestSingleFileSync(String sourcePath, Map<String, Object> metadata) throws Exception {
        Path path = Path.of(sourcePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Path must be a regular file: " + path);
        }
        if (!parserFactory.isSupported(path)) {
            String ext = getFileExtension(path);
            throw new IllegalArgumentException("Unsupported format: '" + ext + "'. " +
                    "Supported: " + String.join(", ", parserFactory.getAllSupportedExtensions()));
        }
        ingestFileSync(path, metadata);
    }

    // Внутренний метод обработки одного файла
    private void ingestFileSync(Path filePath, Map<String, Object> baseMetadata) throws Exception {
        String content = parserFactory.getParser(filePath).extractText(filePath);
        if (content == null || content.isBlank()) return;

        List<String> chunks = smartChunk(content, kbConfig.getChunkSize(), kbConfig.getChunkOverlap());

        Map<String, Object> meta = new HashMap<>(baseMetadata);
        meta.put("source", filePath.toAbsolutePath().toString());
        meta.put("file_type", getFileExtension(filePath));
        meta.put("ingested_at", System.currentTimeMillis());

        processAndStoreChunks(filePath.toString(), chunks, meta);
    }

    /**
     * 🔑 Qdrant + Spring AI автоматически вызывают embeddingModel.embed() внутри vectorStore.add()
     */
    private void processAndStoreChunks(String source, List<String> chunks, Map<String, Object> baseMetadata) {
        if (chunks.isEmpty()) return;

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunkMeta = new HashMap<>(baseMetadata);
            chunkMeta.put("chunk_index", i);
            chunkMeta.put("total_chunks", chunks.size());
            chunkMeta.put("source", source);
            documents.add(new Document(UUID.randomUUID().toString(), chunks.get(i), chunkMeta));
        }

        vectorStore.add(documents);
        log.info("📊 Generated & stored {} embeddings in Qdrant for source: {}", documents.size(), source);
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
