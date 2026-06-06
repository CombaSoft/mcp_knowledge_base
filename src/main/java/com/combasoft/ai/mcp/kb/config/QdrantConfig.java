package com.combasoft.ai.mcp.kb.config;

import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
@Component
public class QdrantConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantConfig.class);
    @Value("${spring.ai.vectorstore.qdrant.collection-name:kb_documents}")
    private String collectionName;

    @Value("${spring.ai.openai.embedding.dimensions:1024}")
    private int dimensions;

    @Value("${spring.ai.openai.embedding.model:text-embedding-qwen3-embedding-0.6b}")
    String model;

    @Value("${spring.ai.openai.api-key:not-needed}")
    String apiKey;

    @Value("${spring.ai.openai.embedding.base-url:http://127.0.0.1:1234}")
    String embeddingBaseUrl;

    public String getCollectionName() {
        return collectionName;
    }

    /**
     * Явное создание VectorStore. Гарантирует, что коллекция создаётся с нужной размерностью.
     */
    @Bean(name = "kbVectorStore")
    public VectorStore vectorStore(QdrantClient qdrantClient, OpenAiApi openAiApi) {
        // Параметры: client, embeddingModel, collectionName, dimensions, initializeSchema

        log.info("[QdrantConfig] Start");

        OpenAiApi customOpenAiApi = openAiApi.mutate().baseUrl(embeddingBaseUrl).build();

        OpenAiEmbeddingModel embeddingModelCustom = new OpenAiEmbeddingModel(
                customOpenAiApi,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .dimensions(dimensions)
                        .build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE);

        log.info("[QdrantConfig] embeddingModel dimensions: " + embeddingModelCustom.dimensions());

        return QdrantVectorStore.builder(qdrantClient, embeddingModelCustom)
                .collectionName(collectionName)
                .initializeSchema(true)
                .build();
    }
}