package com.mark.knowledge.rag.common;

import lombok.Data;

import java.util.Map;

@Data
public class FileProcessResult {
    private String filename;
    private boolean success;
    private String message;
    private String errorMessage;
    private Integer embeddingCount;
    private Map<String, Object> metadata;
}
