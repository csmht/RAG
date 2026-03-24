package com.mark.knowledge.rag.dto;

/**
 * 文档处理响应。
 */
public record DocumentResponse(
    String documentId,
    String filename,
    String message,
    int segmentCount
) {
    public static DocumentResponse success(String documentId, String filename, int segmentCount) {
        return new DocumentResponse(
            documentId,
            filename,
            "文档处理成功",
            segmentCount
        );
    }

    public static DocumentResponse error(String message) {
        return new DocumentResponse(
            null,
            null,
            message,
            0
        );
    }
}
