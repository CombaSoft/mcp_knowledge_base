package com.combasoft.ai.mcp.kb.service.search.reranking;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
public class LlamaRerankerClient {

    private final WebClient webClient;
    private final KbConfig kbConfig;

    public LlamaRerankerClient(WebClient.Builder builder, KbConfig kbConfig) {
        this.webClient = builder.baseUrl("http://localhost:8083").build();
        this.kbConfig = kbConfig;
    }

    public List<RerankResultItem> rerank(String query, List<String> documents) {

        RerankRequest request = new RerankRequest(
                kbConfig.getReranker().model(),
                query,
                documents
        );

        RerankResponse response = webClient.post()
                .uri("/v1/rerank")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .block();

        return response.results;
    }

    public record RerankRequest(
            String model,
            String query,
            List<String> documents
    ) {}

    // Корневой ответ от сервера
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResponse(
            @JsonProperty("model")
            String model,

            @JsonProperty("object")
            String object,

            @JsonProperty("usage")
            RerankUsage usage,

            @JsonProperty("results")
            List<RerankResultItem> results
    ) {}

    // Информация об использовании токенов
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankUsage(
            @JsonProperty("prompt_tokens")
            int promptTokens,

            @JsonProperty("total_tokens")
            int totalTokens
    ) {}

    // Отдельный результат реранкинга
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResultItem(
            @JsonProperty("index")
            int index,

            @JsonProperty("relevance_score")
            double relevanceScore
    ) {}
}
