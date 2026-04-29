package com.mark.knowledge.rag.dto;

import java.util.Map;
import lombok.Data;

@Data
public class ProcessedContentDTO {
    private final boolean success;
    private final String message;
    private final String textContent;
    private final Map<String, Object> metadata;
    private Integer embeddingCount;
}
