package com.combasoft.ai.mcp.kb.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public String extractText(Path filePath) throws Exception {
        try (var document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"pdf"};
    }
}
