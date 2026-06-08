package com.combasoft.ai.mcp.kb.service.search.reranking;

import java.util.List;

public interface Reranker {

    List<Double> score(String query, List<String> documents);
}
