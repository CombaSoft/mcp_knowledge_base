package com.combasoft.ai.mcp.kb.service.ingest;

public enum IngestStatus {

    QUEUED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETED,
    FAILED
}
