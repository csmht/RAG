package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.BatchUploadDTO;
import com.mark.knowledge.rag.dto.BatchUploadTaskStatusDTO;
import com.mark.knowledge.rag.entity.BatchTaskEntity;

public interface BatchUploadService {
    /**
     * 创建批量上传任务
     * @param request 批量上传请求，包含文件列表、用户ID、知识库、分类、标签等信息
     * @return 创建的任务ID
     */
    String create(BatchUploadDTO request);

    /**
     * 异步处理批量上传任务
     * @param task 批量上传任务实体
     * @param request 批量上传请求，包含用户ID、知识库、分类、标签等信息
     */
    void processBatchTaskAsync(BatchTaskEntity task, BatchUploadDTO request);

    /**
     * 获取批量上传任务状态
     * @param taskId 任务ID
     * @return 任务状态DTO
     */
    BatchUploadTaskStatusDTO getTaskStatus(String taskId);
}