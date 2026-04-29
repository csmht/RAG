package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.common.MultiFormatProcessResult;
import org.springframework.web.multipart.MultipartFile;

public interface MultiFormatDocumentService {

    /**
     * 处理多格式文件上传
     */
    MultiFormatProcessResult processMultiFormatFile(MultipartFile file, String userId);
}