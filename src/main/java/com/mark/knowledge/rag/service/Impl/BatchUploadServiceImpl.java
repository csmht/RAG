package com.mark.knowledge.rag.service.Impl;

import com.mark.knowledge.auth.common.Result;
import com.mark.knowledge.rag.common.ValidationResult;
import com.mark.knowledge.rag.dto.BatchUploadDTO;
import com.mark.knowledge.rag.dto.BatchUploadTaskStatusDTO;
import com.mark.knowledge.rag.dto.ProcessedFileDTO;
import com.mark.knowledge.rag.entity.BatchTaskEntity;
import com.mark.knowledge.rag.entity.BatchFileResultEntity;
import com.mark.knowledge.rag.repository.BatchTaskRepository;
import com.mark.knowledge.rag.repository.BatchFileResultRepository;
import com.mark.knowledge.rag.repository.UploadedFileRepository;
import com.mark.knowledge.rag.service.BatchUploadService;
import com.mark.knowledge.rag.service.MultiFormatDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class BatchUploadServiceImpl implements BatchUploadService {

    private static final Logger log = LoggerFactory.getLogger(BatchUploadServiceImpl.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "txt", "md", "jpg", "jpeg", "png", "bmp", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    );

    private static final int MAX_BATCH_SIZE = 50;
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private static final Semaphore DB_WRITE_SEMAPHORE = new Semaphore(3);

    private final MultiFormatDocumentService multiFormatService;
    private final BatchTaskRepository batchTaskRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final BatchFileResultRepository batchFileResultRepository;

    public BatchUploadServiceImpl(MultiFormatDocumentService multiFormatService,
                                  BatchTaskRepository batchTaskRepository,
                                  UploadedFileRepository uploadedFileRepository,
                                  BatchFileResultRepository batchFileResultRepository) {
        this.multiFormatService = multiFormatService;
        this.batchTaskRepository = batchTaskRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.batchFileResultRepository = batchFileResultRepository;
    }

    @Transactional
    @Override
    public String create(BatchUploadDTO request) {
        if (request.getFiles() == null || request.getFiles().isEmpty()) {
            throw new IllegalArgumentException("文件列表不能为空");
        }

        String taskId = UUID.randomUUID().toString();

        if (request.getFiles().size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    String.format("批量上传文件数量超过限制: %d > %d", request.getFiles().size(), MAX_BATCH_SIZE));
        }

        for (MultipartFile file : request.getFiles()) {
            ValidationResult validation = validateFile(file);
            String message = validation.getMessage();
            if (message == null) {
                message = "未知错误";
            }
            if (!validation.isValid()) {
                throw new IllegalArgumentException(
                        String.format("文件验证失败: %s - %s", file.getOriginalFilename(), message));
            }
        }

        BatchTaskEntity task = new BatchTaskEntity();
        task.setTaskId(taskId);
        task.setUserId(request.getUserId());
        task.setKnowledgeBase(request.getKnowledgeBase() != null ? request.getKnowledgeBase() : "default");
        task.setCategory(request.getCategory());
        task.setTags(request.getTags() != null ? String.join(",", request.getTags()) : null);
        task.setTotalFiles(request.getFiles().size());
        task.setStatus(BatchTaskEntity.BatchTaskStatus.CREATED);
        task.setProgressPercentage(0);
        task.setMessage("任务已创建");
        batchTaskRepository.save(task);

        log.info("创建批量上传任务: ID={}, 文件数={}, 知识库={}", taskId, request.getFiles().size(), request.getKnowledgeBase());

        // 启动异步处理，传入原始请求
        processBatchTaskAsync(task, request);

        return taskId;
    }

    private ProcessedFileDTO processFileSequential(MultipartFile file, BatchUploadDTO request) {
        ProcessedFileDTO result = new ProcessedFileDTO();
        result.setOriginalFilename(file.getOriginalFilename());

        try {
            ValidationResult validation = validateFile(file);
            if (!validation.isValid()) {
                result.setSuccess(false);
                result.setErrorMessage(validation.getMessage());
                return result;
            }

            if (isDuplicateFile(file)) {
                result.setSuccess(false);
                result.setErrorMessage("文件已存在");
                return result;
            }

            DB_WRITE_SEMAPHORE.acquire();
            try {
                Result<ProcessedFileDTO> processResult = multiFormatService.processMultiFormatFile(file, request.getUserId());

                if (processResult.getCode() == 1 && processResult.getData() != null) {
                    ProcessedFileDTO data = processResult.getData();
                    result.setSuccess(data.isSuccess());
                    result.setMessage(data.getMessage());
                    result.setFilePath(data.getFilePath());
                    result.setEmbeddingCount(data.getEmbeddingCount());
                    result.setMetadata(data.getMetadata());
                    result.setTextContent(data.getTextContent());
                    if (!data.isSuccess()) {
                        result.setErrorMessage(data.getErrorMessage());
                    }
                } else {
                    result.setSuccess(false);
                    result.setErrorMessage(processResult.getMsg());
                }
            } finally {
                DB_WRITE_SEMAPHORE.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.setSuccess(false);
            result.setErrorMessage("处理被中断: " + e.getMessage());
        } catch (Exception e) {
            log.error("处理文件时发生异常: {}", file.getOriginalFilename(), e);
            result.setSuccess(false);
            result.setErrorMessage("处理失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 异步处理批量任务
     */
    @Async
    @Override
    public void processBatchTaskAsync(BatchTaskEntity task, BatchUploadDTO request) {
        try {
            task.setStatus(BatchTaskEntity.BatchTaskStatus.PROCESSING);
            batchTaskRepository.save(task);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            AtomicInteger processedCount = new AtomicInteger(0);

            List<MultipartFile> files = request.getFiles();

            List<ProcessedFileDTO> results = new ArrayList<>();
            for (MultipartFile file : files) {
                ProcessedFileDTO result = processFileSequential(file, request);
                results.add(result);

                if (result.isSuccess()) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }

                // 更新进度
                int currentProcessed = processedCount.incrementAndGet();
                int progress = (int) ((double) currentProcessed / files.size() * 100);
                task.setProgressPercentage(progress);
                if (progress < 100) {
                    batchTaskRepository.save(task);
                }
            }

            // 存储结果到数据库
            saveBatchFileResults(task.getTaskId(), results);

            task.setSuccessCount(successCount.get());
            task.setFailureCount(failureCount.get());
            task.setStatus(BatchTaskEntity.BatchTaskStatus.COMPLETED);
            task.setCompletedTime(new Date());
            task.setProgressPercentage(100);
            task.setMessage("任务已完成");
            batchTaskRepository.save(task);

            log.info("批量上传任务完成: ID={}, 成功={}, 失败={}",
                    task.getTaskId(), successCount.get(), failureCount.get());

        } catch (Exception e) {
            log.error("批量上传任务处理失败: {}", task.getTaskId(), e);
            task.setStatus(BatchTaskEntity.BatchTaskStatus.FAILED);
            task.setErrorMessage("批量处理失败: " + e.getMessage());
            task.setProgressPercentage(0);
            batchTaskRepository.save(task);
        }
    }

    /**
     * 保存批量文件处理结果到数据库
     */
    private void saveBatchFileResults(String taskId, List<ProcessedFileDTO> results) {
        for (ProcessedFileDTO result : results) {
            BatchFileResultEntity entity = new BatchFileResultEntity();
            entity.setTaskId(taskId);
            entity.setFileName(result.getOriginalFilename());
            entity.setSuccess(result.isSuccess());
            entity.setErrorMessage(result.getErrorMessage());
            entity.setEmbeddingCount(result.getEmbeddingCount() != null ? result.getEmbeddingCount() : 0);
            entity.setFilePath(result.getFilePath());
            entity.setProcessedTime(LocalDateTime.now());
            batchFileResultRepository.save(entity);
        }
    }

    /**
     * 验证文件
     *
     * @param file 要验证的文件
     * @return 验证结果
     */
    private ValidationResult validateFile(MultipartFile file) {
        String filename = file.getOriginalFilename();

        if (filename == null || filename.isBlank()) {
            return new ValidationResult(false, "文件名不能为空");
        }

        // 检查文件扩展名
        String extension = getFileExtension(filename).toLowerCase();
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            return new ValidationResult(false, "不支持的文件类型: ." + extension + "，支持的格式: " + String.join(", ", SUPPORTED_EXTENSIONS));
        }

        // 检查空文件
        if (file.isEmpty()) {
            return new ValidationResult(false, "文件为空，无法处理");
        }

        // 检查文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            double maxSizeMB = MAX_FILE_SIZE / (1024.0 * 1024.0);
            double fileSizeMB = file.getSize() / (1024.0 * 1024.0);
            return new ValidationResult(false, String.format("文件大小超过限制: %.2f MB > %.2f MB", fileSizeMB, maxSizeMB));
        }

        // 额外检查：检查文件内容是否真的是有效的文件类型
        try {
            if ("pdf".equals(extension)) {
                // 对PDF文件进行基本验证
                if (file.getSize() >= 4 && !isValidPdfFile(file)) {
                    return new ValidationResult(false, "PDF文件格式无效");
                }
            } else if (extension.matches("^(jpg|jpeg|png|bmp|gif)$")) {
                // 对图像文件进行基本验证
                if (!isValidImageFile(file)) {
                    return new ValidationResult(false, "图像文件格式无效");
                }
            }
        } catch (Exception e) {
            log.warn("文件格式验证异常: {}", e.getMessage());
            return new ValidationResult(false, "文件格式验证失败: " + e.getMessage());
        }

        return new ValidationResult(true, "验证通过");
    }

    /**
     * 验证PDF文件头
     */
    private boolean isValidPdfFile(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            // PDF文件以 %PDF- 开头
            if (bytes.length < 5) {
                return false;
            }
            String header = new String(bytes, 0, 5);
            return header.startsWith("%PDF-");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 验证图像文件格式
     */
    private boolean isValidImageFile(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length < 10) {
                return false;
            }

            // JPEG 文件头
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
                return true;
            }
            // PNG 文件头
            if (bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50 &&
                    bytes[2] == (byte) 0x4E && bytes[3] == (byte) 0x47) {
                return true;
            }
            // GIF 文件头
            if (bytes[0] == (byte) 0x47 && bytes[1] == (byte) 0x49 &&
                    bytes[2] == (byte) 0x46) {
                return true;
            }
            // BMP 文件头
            return bytes[0] == (byte) 0x42 && bytes[1] == (byte) 0x4D;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否为重复文件
     *
     * @param file 上传的文件
     * @return true表示是重复文件，false表示不是重复文件
     */
    private boolean isDuplicateFile(MultipartFile file) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                return false;
            }

            // 计算文件哈希值进行精确重复检测
            byte[] fileBytes = file.getBytes();
            String fileHash = calculateFileHash(fileBytes);

            if (fileHash == null) {
                log.warn("无法计算文件哈希值，回退到文件名检查: {}", originalFilename);
                // 如果无法计算哈希值，使用文件名检查
                return uploadedFileRepository.existsByOriginalFilename(originalFilename);
            }

            // 检查是否有相同哈希值的文件（更精确地重复检测）
            return uploadedFileRepository.existsByFileHash(fileHash);

        } catch (Exception e) {
            log.warn("检查重复文件时发生异常: {}", e.getMessage());
            // 出现异常时，默认不认为是重复文件，避免阻止正常上传
            return false;
        }
    }

    /**
     * 计算文件哈希值（用于精确重复检测）
     */
    private String calculateFileHash(byte[] fileBytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("计算文件哈希值失败", e);
            return null;
        }
    }

    @Override
    public BatchUploadTaskStatusDTO getTaskStatus(String taskId) {
        BatchTaskEntity task = batchTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        // 获取当前登录用户
        String currentUserId = getCurrentUsername();

        // 权限校验：只有任务创建者或管理员可以查看任务状态
        if (!task.getUserId().equals(currentUserId) && !hasAdminRole(currentUserId)) {
            throw new IllegalArgumentException("无权限查看此任务状态");
        }

        BatchUploadTaskStatusDTO dto = new BatchUploadTaskStatusDTO();
        dto.setTaskId(task.getTaskId());
        dto.setStatus(task.getStatus().name());
        dto.setTotalFiles(task.getTotalFiles());
        dto.setSuccessCount(task.getSuccessCount());
        dto.setFailureCount(task.getFailureCount());
        // 从数据库获取结果
        List<BatchFileResultEntity> resultEntities = batchFileResultRepository.findByTaskId(taskId);
        List<ProcessedFileDTO> results = resultEntities.stream()
                .map(this::convertToProcessedFileDTO)
                .collect(Collectors.toList());
        dto.setResults(results);
        dto.setErrorMessage(task.getErrorMessage());
        dto.setCreatedTime(task.getCreatedTime());
        dto.setCompletedTime(task.getCompletedTime());
        return dto;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex + 1);
        }
        return "";
    }

    private ProcessedFileDTO convertToProcessedFileDTO(BatchFileResultEntity entity) {
        ProcessedFileDTO result = new ProcessedFileDTO();
        result.setOriginalFilename(entity.getFileName());
        result.setSuccess(entity.isSuccess());
        result.setErrorMessage(entity.getErrorMessage());
        result.setEmbeddingCount(entity.getEmbeddingCount());
        result.setMessage(entity.isSuccess() && entity.getEmbeddingCount() == 0 ? "文件已保存，不进行内容处理" : null);
        result.setFilePath(entity.getFilePath());
        return result;
    }

    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() &&
            !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return "unknown";
    }

    private boolean hasAdminRole(String username) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

}