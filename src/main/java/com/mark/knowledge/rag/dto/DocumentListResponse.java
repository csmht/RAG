package com.mark.knowledge.rag.dto;

import java.util.List;

/**
 * 文档列表响应。
 */
public record DocumentListResponse(
    List<DocumentListItemResponse> documents,
    int total
) {
}
