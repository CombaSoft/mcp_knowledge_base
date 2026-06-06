package com.combasoft.ai.mcp.kb.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class TextDocumentParser implements DocumentParser {

    private static final List<String> SUPPORTED = List.of(
            "txt", "md", "java", "xml", "properties", "yml", "yaml",
            "json", "py", "js", "ts", "html", "css", "sql", "log"
    );

    @Override
    public String extractText(Path filePath) throws Exception {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    @Override
    public String[] getSupportedExtensions() {
        return SUPPORTED.toArray(new String[0]);
    }
}
