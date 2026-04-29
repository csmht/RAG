package com.mark.knowledge.rag.dto;

import com.mark.knowledge.rag.common.FileProcessResult;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class BatchUploadTaskStatusDTO {
    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 总文件数
     */
    private Integer totalFiles;

    /**
     * 成功数量
     */
    private Integer successCount;

    /**
     * 失败数量
     */
    private Integer failureCount;

    /**
     * 文件处理结果列表
     */
    private List<FileProcessResult> results;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private Date createdTime;

    /**
     * 完成时间
     */
    private Date completedTime;
}