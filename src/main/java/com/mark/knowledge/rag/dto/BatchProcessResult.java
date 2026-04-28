package com.mark.knowledge.rag.dto;

public record BatchProcessResult(
        String filename,
        boolean success,
        String documentId,
        int segmentCount,
        String errorMessage
) {
    public static BatchProcessResult success(String documentId, String filename, int segmentCount) {
        return new BatchProcessResult(filename, true, documentId, segmentCount, null);
    }
    public static BatchProcessResult failure(String filename, String errorMessage) {
        return new BatchProcessResult(filename, false, null, 0, errorMessage);
    }
}
