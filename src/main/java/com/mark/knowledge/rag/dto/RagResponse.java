package com.mark.knowledge.rag.dto;

import java.util.List;

/**
 * RAG 问答响应。
 */
public record RagResponse(
    String answer,
    String conversationId,
    List<SourceReference> sources
) {
}
