package com.mark.knowledge.rag.dto;

/**
 * RAG 问答请求。
 */
public record RagRequest(
    String question,
    String conversationId,
    Integer maxResults
) {
    public RagRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
    }

    public static RagRequest of(String question) {
        return new RagRequest(question, null, 5);
    }
}
