package com.mark.knowledge.rag.service;

import com.mark.knowledge.auth.common.Result;
import com.mark.knowledge.rag.dto.ProcessedFileDTO;
import org.springframework.web.multipart.MultipartFile;

public interface MultiFormatDocumentService {

    /**
     * 处理多格式文件上传
     */
    Result<ProcessedFileDTO> processMultiFormatFile(MultipartFile file, String userId);
}