package com.mark.knowledge.rag.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ProcessedFileDTO {
    private String originalFilename;
    private String filePath;
    private boolean success;
    private String message;
    private String errorMessage;
    private Integer embeddingCount;
    private String textContent;
    private Map<String, Object> metadata;
}