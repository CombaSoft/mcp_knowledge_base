package com.combasoft.ai.mcp.kb.service.reranking;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    private final ChatClient chatClient;
    private final KbConfig kbConfig;

    // 🔑 1. Record для отдельной оценки
    public record ScoreResult(int index, double score) {}

    // 🔑 2. Record-обёртка для списка (Spring AI любит объекты на корневом уровне JSON)
    public record RerankResponse(List<ScoreResult> results) {}

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
                .collect(Collectors.joining("\n\n"));

        // 🔑 3. Обновленный промпт: просим вернуть объект с ключом "results"
        String promptText = """
                You are an expert search relevance evaluator. 
                Given a user query and a list of documents, evaluate the relevance of each document to the query on a scale from 0.0 (completely irrelevant) to 1.0 (perfectly answers the query).
                
                Return ONLY a valid JSON object with a single key "results", which is an array of objects. 
                Each object in the array must have exactly two fields:
                - "index": the original 0-based integer index of the document from the list below.
                - "score": the relevance score as a double between 0.0 and 1.0.
                
                Do not include any markdown formatting (like ```json), explanations, or text outside the JSON object.
                
                Query: "%s"
                
                Documents:
                %s
                """.formatted(query, documentsText);

        try {
            // 🔑 4. Правильный конструктор: передаем ТОЛЬКО класс обёртки
            var converter = new BeanOutputConverter<>(RerankResponse.class);

            ChatOptions options = OpenAiChatOptions.builder()
                    .model(kbConfig.getReranker().model())
                    .temperature(0.1)
                    .maxTokens(512) // Чуть увеличили, так как JSON с обёрткой чуть длиннее
                    .build();

            // 🔑 5. Получаем сразу типизированный объект RerankResponse
            RerankResponse response = chatClient.prompt()
                    .user(promptText)
                    .options(options)
                    .call()
                    .entity(converter);

            if (response == null || response.results() == null) {
                throw new IllegalStateException("LLM returned null or empty results");
            }

            // Преобразуем результат в Map для быстрого поиска по индексу
            Map<Integer, Double> scoreMap = response.results().stream()
                    .collect(Collectors.toMap(ScoreResult::index, ScoreResult::score));

            // Формируем итоговый список оценок, сохраняя исходный порядок документов
            List<Double> finalScores = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                if (i < limit) {
                    finalScores.add(scoreMap.getOrDefault(i, 0.1));
                } else {
                    finalScores.add(0.1);
                }
            }

            log.debug("✅ LLM Reranker successfully scored {} documents", finalScores.size());
            return finalScores;

        } catch (Exception e) {
            log.error("❌ LLM Reranker failed, falling back to original vector scores", e);
            return documents.stream().map(d -> 0.5).toList();
        }
    }
}