package com.mark.knowledge.rag.dto;

/**
 * 文档列表项响应。
 */
public record DocumentListItemResponse(
    String documentId,
    String filename,
    int segmentCount
) {
}
