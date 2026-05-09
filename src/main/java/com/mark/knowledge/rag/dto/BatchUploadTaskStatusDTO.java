package com.mark.knowledge.rag.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class BatchUploadTaskStatusDTO {
    private String taskId;
    private String status;
    private Integer totalFiles;
    private Integer successCount;
    private Integer failureCount;
    private List<ProcessedFileDTO> results;
    private String errorMessage;
    private Date createdTime;
    private Date completedTime;
}