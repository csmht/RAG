package com.mark.knowledge.rag.dto;

import lombok.Data;

@Data
public class MultiFormatDocumentDTO {
    /**
     * 文档文件名
     */
    private final String filename;
    /**
     * 文档文件类型
     */
    private final String fileType;
    /**
     * 处理结果消息
     */
    private final String message;
    /**
     * 嵌入向量数量
     */
    private final Integer embeddingCount;
    /**
     * 文档文本内容
     */
    private final String textContent;
    /**
     * 文档元数据
     */
    private final Object metadata;
}