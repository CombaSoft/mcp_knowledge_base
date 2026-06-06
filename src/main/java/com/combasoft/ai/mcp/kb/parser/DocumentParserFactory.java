package com.combasoft.ai.mcp.kb.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.*;

@Component
public class DocumentParserFactory {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserFactory.class);
    private final Map<String, DocumentParser> extensionToParser = new HashMap<>();
    // Set всех поддерживаемых расширений для быстрой проверки
    private final Set<String> allSupportedExtensions = new HashSet<>();

    public DocumentParserFactory(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            for (String ext : parser.getSupportedExtensions()) {
                String lowerExt = ext.toLowerCase();
                extensionToParser.put(lowerExt, parser);
                allSupportedExtensions.add(lowerExt);
            }
        }
        log.info("📄 Registered parsers for extensions: {}", allSupportedExtensions);
    }

    public DocumentParser getParser(Path filePath) {
        String ext = getFileExtension(filePath).toLowerCase();
        DocumentParser parser = extensionToParser.get(ext);
        if (parser == null) {
            log.warn("No specific parser for '{}'. Falling back to text parser.", ext);
            return new TextDocumentParser();
        }
        return parser;
    }

    private String getFileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(dot + 1) : "";
    }

    /**
     * 🔑 Возвращает множество всех поддерживаемых расширений (без точки).
     * Удобно для фильтрации файлов и валидации входных параметров.
     */
    public Set<String> getAllSupportedExtensions() {
        return Collections.unmodifiableSet(allSupportedExtensions);
    }

    /**
     * Проверяет, поддерживается ли файл по расширению.
     */
    public boolean isSupported(Path filePath) {
        String ext = getFileExtension(filePath).toLowerCase();
        return allSupportedExtensions.contains(ext);
    }
}
