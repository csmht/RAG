package com.mark.knowledge.rag.dto;

public record DocumentUploadResponse(
    String filename,
    String message,
    Integer embeddingCount,
    String textContent,
    String filePath,
    Boolean success
) {
}
