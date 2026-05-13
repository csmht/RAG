package com.mark.knowledge.rag.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
public class BatchUploadDTO {
    /**
     * 文件列表
     */
    private List<MultipartFile> files;

    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 知识库标识
     */
    private String knowledgeBase;
    
    /**
     * 文档分类
     */
    private String category;
    
    /**
     * 标签列表
     */
    private List<String> tags;
}