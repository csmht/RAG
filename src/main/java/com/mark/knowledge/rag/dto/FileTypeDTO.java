package com.mark.knowledge.rag.dto;

import lombok.Data;

@Data
public class FileTypeDTO {
    private final String type;
    private final String extension;
    private final String contentType;
}
