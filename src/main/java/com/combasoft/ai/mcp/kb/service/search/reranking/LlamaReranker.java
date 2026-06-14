package com.combasoft.ai.mcp.kb.service.search.reranking;

import com.combasoft.ai.mcp.kb.config.KbConfig;
import com.combasoft.ai.mcp.kb.service.search.SearchProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@Service("llamaReranker")
public class LlamaReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlamaReranker.class);

    private final LlamaRerankerClient rerankerClient;
    private final KbConfig kbConfig;
    private final SearchProgressTracker tracker;

    public LlamaReranker(LlamaRerankerClient rerankerClient, KbConfig kbConfig, SearchProgressTracker tracker) {
        this.rerankerClient = rerankerClient;
        this.kbConfig = kbConfig;
        this.tracker = tracker;
    }


    @Override
    public List<Double> score(String query, List<String> documents, String taskId) {
        if (!kbConfig.getReranker().enabled() || documents == null || documents.isEmpty()) {
            return documents.stream().map(d -> 1.5).toList();
        }

        try {

            int documentsCount = documents.size();
            int order = 0;
            int progressStep = 55 / documentsCount;
            int currentProgress = 30;

            List<LlamaRerankerClient.RerankResultItem> listResults = new ArrayList<>();

            int chunkSize = 10;

            List<List<String>> batches = IntStream.range(0, (documents.size() + chunkSize - 1) / chunkSize)
                    .mapToObj(i -> documents.subList(i * chunkSize, Math.min((i + 1) * chunkSize, documents.size())))
                    .map(ArrayList::new) // Делаем независимые копии для безопасности
                    .collect(Collectors.toList());

            for (List<String> batchItem: batches) {

                for (LlamaRerankerClient.RerankResultItem item : rerankerClient.rerank(query, batchItem)) {
                    listResults.add(new LlamaRerankerClient.RerankResultItem(order, item.relevanceScore()));
                    order = order + 1;

                    currentProgress += progressStep;

                    tracker.updateProgress(taskId, currentProgress,
                            "Произведена оценка релевантности документа № %d из %d".formatted(order, documentsCount));
                }
            }

            if (listResults.isEmpty()) {
                throw new IllegalStateException("Parsed results list is empty");
            }

            Map<Integer, Double> scoreMap = listResults.stream()
                    .collect(Collectors.toMap(LlamaRerankerClient.RerankResultItem::index,
                            LlamaRerankerClient.RerankResultItem::relevanceScore));

            log.info("✅ LlamaReranker successfully scored documents with raw scores {} ", scoreMap);

            List<Double> finalScores = new ArrayList<>();

            for (int i = 0; i < documents.size(); i++) {
                double score = scoreMap.getOrDefault(i, 0.1);
                finalScores.add(Math.max(0.0, Math.min(1.0, score)));
            }

            log.info("✅ LlamaReranker successfully scored {} documents with scores {} ", finalScores.size(), finalScores);

            return finalScores;

        } catch (Exception e) {
            log.error("❌ LlamaReranker failed. Falling back to original vector scores. Error: {}", e.getMessage());
            return documents.stream().map(d -> 1.5).toList();
        }
    }
}

