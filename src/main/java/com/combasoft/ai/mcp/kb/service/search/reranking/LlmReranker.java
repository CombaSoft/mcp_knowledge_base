package com.combasoft.ai.mcp.kb.service.search.reranking;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatClient chatClient;
    private final KbConfig kbConfig;

    // 🔑 Используем объект с индексом. Для 4B модели это надежнее, чем плоский массив,
    // так как позволяет точно сопоставить оценку с документом, даже если модель пропустит один.
    public record ScoreResult(int index, double score) {}

    public LlmReranker(ChatClient.Builder chatClientBuilder, KbConfig kbConfig) {
        this.chatClient = chatClientBuilder.build();
        this.kbConfig = kbConfig;
    }

    @Override
    public List<Double> score(String query, List<String> documents) {
        if (!kbConfig.getReranker().enabled() || documents == null || documents.isEmpty()) {
            return documents.stream().map(d -> 0.5).toList();
        }

        int limit = Math.min(documents.size(), kbConfig.getReranker().maxDocsToRerank());
        List<String> docsToRerank = documents.subList(0, limit);

        String documentsText = IntStream.range(0, docsToRerank.size())
                .mapToObj(i -> String.format("[%d] %s", i, docsToRerank.get(i)))
                .collect(Collectors.joining("\n"));

        // ПРОМПТ ДЛЯ 4B МОДЕЛИ
        String systemPrompt = """
                You are a strict, deterministic search relevance evaluation engine. 
                Your ONLY output must be a valid JSON array. 
                Do not include ANY markdown formatting (like ```json), explanations, reasoning, or conversational text.
                """;

        String userPrompt = """
                Evaluate the relevance of each provided document to the user's query on a scale from 0.0 (completely irrelevant) to 1.0 (perfect match).
                
                STRICT RULES:
                1. Output MUST be a JSON array of objects.
                2. Each object MUST have exactly two fields: "index" (integer, matching the document's bracketed number) and "score" (float, 0.0 to 1.0).
                3. The array MUST contain exactly %d items, one for each document provided.
                4. Start your response immediately with '[' and end with ']'.
                
                Query: "%s"
                
                Documents:
                %s
                """.formatted(docsToRerank.size(), query, documentsText);

        try {
            ChatOptions options = OpenAiChatOptions.builder()
                    .model(kbConfig.getReranker().model())
                    .temperature(0.1) // Низкая температура для детерминированного вывода
                    .maxTokens(512)   // 4B модели нужно чуть больше места для корректного JSON, 512 более чем достаточно
                    .build();

            // 🔑 Используем разделение на system и user промпты
            String rawResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(options)
                    .call()
                    .content();

            log.debug("🔍 RAW LLM Reranker Response:\n{}", rawResponse);

            if (rawResponse == null || rawResponse.trim().isEmpty()) {
                throw new IllegalStateException("LLM returned empty response");
            }

            // Вырезаем всё, кроме содержимого квадратных скобок
            // Это страховка на случай, если модель всё же добавит ```json ... ```
            Pattern pattern = Pattern.compile("\\[.*\\]", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(rawResponse);

            if (!matcher.find()) {
                throw new IllegalStateException("No JSON array found in LLM response. Raw: " + rawResponse);
            }

            String cleanJsonArray = matcher.group();

            // Парсим в список объектов ScoreResult
            List<ScoreResult> results = objectMapper.readValue(
                    cleanJsonArray,
                    new TypeReference<List<ScoreResult>>() {}
            );

            if (results == null || results.isEmpty()) {
                throw new IllegalStateException("Parsed results list is empty");
            }

            // Преобразуем в Map для безопасного сопоставления по индексу
            Map<Integer, Double> scoreMap = results.stream()
                    .collect(Collectors.toMap(ScoreResult::index, ScoreResult::score));

            List<Double> finalScores = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                if (i < limit) {
                    // Берем оценку по индексу, или 0.1 если модель по ошибке пропустила этот индекс
                    double score = scoreMap.getOrDefault(i, 0.1);
                    finalScores.add(Math.max(0.0, Math.min(1.0, score)));
                } else {
                    finalScores.add(0.1);
                }
            }

            log.info("✅ LLM Reranker (4B) successfully scored {} documents", finalScores.size());
            return finalScores;

        } catch (Exception e) {
            log.error("❌ LLM Reranker failed. Falling back to original vector scores. Error: {}", e.getMessage());
            return documents.stream().map(d -> 0.5).toList();
        }
    }
}