package com.mark.knowledge.rag.common;

import com.mark.knowledge.rag.dto.ProcessedFileDTO;
import lombok.Data;

@Data
public class MultiFormatProcessResult {
    private final boolean success;
    private final String message;
    private final ProcessedFileDTO fileInfo;
}
