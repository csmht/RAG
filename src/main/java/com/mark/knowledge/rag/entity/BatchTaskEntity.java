package com.mark.knowledge.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "batch_tasks", indexes = {
        @Index(name = "idx_task_id", columnList = "task_id"),
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_status", columnList = "status")
})
public class BatchTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "knowledge_base", length = 100)
    private String knowledgeBase;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "tags", columnDefinition = "TEXT")
    private String tags;

    @Column(name = "total_files")
    private Integer totalFiles;

    @Column(name = "success_count")
    private Integer successCount;

    @Column(name = "failure_count")
    private Integer failureCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BatchTaskStatus status;

    @Column(name = "progress_percentage")
    private Integer progressPercentage;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_time", nullable = false, updatable = false)
    private Date createdTime;

    @UpdateTimestamp
    @Column(name = "updated_time")
    private Date updatedTime;

    @Column(name = "completed_time")
    private Date completedTime;

    public enum BatchTaskStatus {
        CREATED,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
