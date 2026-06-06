package com.combasoft.ai.mcp.kb.parser;

import java.nio.file.Path;

public interface DocumentParser {

    String extractText(Path filePath) throws Exception;
    String[] getSupportedExtensions();
}
