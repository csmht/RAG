package com.mark.knowledge.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量上传单个文件处理结果实体
 *
 * @author mark
 */
@Data
@Entity
@Table(name = "batch_file_results")
public class BatchFileResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的批量任务ID */
    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    /** 文件名 */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** 处理是否成功 */
    @Column(name = "is_success", nullable = false)
    private boolean success;

    /** 错误信息（如果失败） */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 生成的向量数量 */
    @Column(name = "embedding_count")
    private int embeddingCount;

    /** 文件存储路径 */
    @Column(name = "file_path")
    private String filePath;

    /** 处理时间 */
    @Column(name = "processed_time")
    private LocalDateTime processedTime;

}