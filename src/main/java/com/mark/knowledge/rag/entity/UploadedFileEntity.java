package com.mark.knowledge.rag.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上传文件实体类
 */
@Data
@Entity
@Table(name = "uploaded_files")
public class UploadedFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    // PDF, IMAGE, DOCUMENT, TEXT
    private String fileType;

    @Column(nullable = false)
    private String filePath;

    @Column
    private Long fileSize;

    @Column
    private String contentType;

    @Column
    private Integer embeddingCount;

    @Column
    private String processedText;

    @Column(nullable = false)
    @CreationTimestamp
    private LocalDateTime uploadTime;

    @Column
    private LocalDateTime processedTime;

    @Column
    // UPLOADED, PROCESSING, COMPLETED, FAILED
    private String status;

    @Column
    private String errorMessage;

    @Column(length = 64) // SHA-256哈希值长度为64字符
    private String fileHash;

}