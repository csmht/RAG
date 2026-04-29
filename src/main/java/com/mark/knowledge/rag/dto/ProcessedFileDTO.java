package com.mark.knowledge.rag.dto;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;
import lombok.Data;

@Data
public class ProcessedFileDTO {
    private final String originalFilename;
    private final FileTypeDTO fileType;
    private final String filePath;
    private final ProcessedContentDTO processedContentDTO;
    private final List<TextSegment> segments;
}
