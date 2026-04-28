package com.mark.knowledge.rag.dto;

import java.util.List;

/**
 * 批量文档上传响应
 *
 * @param totalFiles 总文件数
 * @param successCount 成功处理的文件数
 * @param failureCount 处理失败的文件数
 * @param results 每个文件的处理结果详情
 */
public record BatchUploadResponse(
        int totalFiles,
        int successCount,
        int failureCount,
        List<BatchProcessResult> results
) {
}