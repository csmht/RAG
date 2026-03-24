package com.mark.knowledge.rag.dto;

/**
 * RAG 来源片段。
 */
public record SourceReference(
    String filename,
    String excerpt,
    double relevanceScore
) {
}
