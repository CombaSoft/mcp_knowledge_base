package com.combasoft.ai.mcp.kb.service.ingest;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CustomTextSplitter {

    /**
     * Разбивает текст на чанки, гарантируя, что ни один чанк не превысит maxSize.
     * Оптимизировано для работы с большими документами (миллионы символов) за миллисекунды.
     *
     * @param text      Исходный текст
     * @param maxSize   Максимальный размер чанка в символах
     * @param overlap   Перекрытие в символах (для сохранения контекста между чанками)
     * @param metadata  Метаданные, которые будут скопированы во все чанки
     * @return Список документов-чанков
     */
    public List<Document> split(String text, int maxSize, int overlap, Map<String, Object> metadata) {
        List<Document> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        int start = 0;
        int chunkIndex = 0;
        // 🔑 КРИТИЧЕСКИ ВАЖНО: Запрещаем создавать чанки меньше половины maxSize,
        // чтобы избежать "ползания" указателя из-за overlap и ранних точек разрыва.
        int minChunkSize = maxSize / 2;

        while (start < text.length()) {
            int end = Math.min(start + maxSize, text.length());

            // Если это не последний чанк, ищем точку разрыва
            if (end < text.length()) {
                // Ищем разрыв только во второй половине окна (от start + minChunkSize до end)
                int searchStart = start + minChunkSize;
                int breakPoint = findBestBreakPoint(text, searchStart, end);

                if (breakPoint > searchStart) {
                    end = breakPoint;
                }
            }

            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                Map<String, Object> chunkMeta = new HashMap<>(metadata);
                chunkMeta.put("chunk_index", chunkIndex++);
                chunks.add(new Document(UUID.randomUUID().toString(), chunkText, chunkMeta));
            }

            // Сдвигаем start с учетом overlap
            start = end - overlap;

            // 🔑 ЗАЩИТА ОТ БЕСКОНЕЧНОГО ЦИКЛА:
            // Если из-за overlap указатель откатился назад или не сдвинулся,
            // принудительно двигаем его вперед на 1 символ (или на end, если это безопаснее)
            if (start <= 0 || start >= end) {
                start = end;
            }
        }

        return chunks;
    }

    /**
     * Ищет лучшую точку разрыва, используя быстрый поиск с конца строки.
     * Не создает новых объектов String (в отличие от substring).
     */
    private int findBestBreakPoint(String text, int searchStart, int end) {
        // end - 1, чтобы не захватить символ, который уже является границей

        // 1. Приоритет: Абзацы
        int p = text.lastIndexOf("\n\n", end - 1);
        if (p >= searchStart) return p + 2;

        // 2. Приоритет: Концы предложений
        int s = text.lastIndexOf(". ", end - 1);
        if (s >= searchStart) return s + 2;

        s = text.lastIndexOf("! ", end - 1);
        if (s >= searchStart) return s + 2;

        s = text.lastIndexOf("? ", end - 1);
        if (s >= searchStart) return s + 2;

        // Русская/английская точка в конце строки или перед пробелом
        s = text.lastIndexOf(".", end - 1);
        if (s >= searchStart && (s == text.length() - 1 || Character.isWhitespace(text.charAt(s + 1)))) {
            return s + 1;
        }

        // 3. Приоритет: Пробел
        int space = text.lastIndexOf(" ", end - 1);
        if (space >= searchStart) return space + 1;

        // 4. Fallback: Жесткий разрез (если попался гигантский URL или base64 без пробелов)
        return end;
    }

    public List<Document> splitMy(String text, int maxSize, int overlap, Map<String, Object> metadata) {

        List<Document> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        int allTextLength = text.length();
        int chunkStart = 0;
        int chunkIndex = 0;
        int chunkEnd = 0;

        while (chunkStart <= allTextLength) {

            chunkEnd = Math.min(chunkStart + maxSize, allTextLength);

            int minChunkSize = maxSize / 2;

            // Если это не последний чанк, ищем точку разрыва
            if (chunkEnd < allTextLength) {
                // Ищем разрыв только во второй половине окна (от start + minChunkSize до end)
                int searchStart = chunkStart + minChunkSize;
                int breakPoint = findBestBreakPoint(text, searchStart, chunkEnd);

                if (breakPoint > searchStart) {
                    chunkEnd = breakPoint;
                }
            }

            String chunkText = text.substring(chunkStart, chunkEnd).trim();

            if (!chunkText.isEmpty()) {
                Map<String, Object> chunkMeta = new HashMap<>(metadata);
                chunkMeta.put("chunk_index", chunkIndex++);
                chunks.add(new Document(UUID.randomUUID().toString(), chunkText, chunkMeta));
            }

            if (chunkEnd == allTextLength) {
                break;
            }

            chunkStart = chunkStart + maxSize - overlap;

            if(chunkStart > allTextLength) {
                chunkStart = allTextLength;
            }
        }
        return chunks;
    }
}