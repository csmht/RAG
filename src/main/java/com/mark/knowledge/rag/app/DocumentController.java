package com.mark.knowledge.rag.app;

import com.mark.knowledge.auth.common.Result;
import com.mark.knowledge.rag.dto.BatchUploadDTO;
import com.mark.knowledge.rag.dto.BatchUploadTaskStatusDTO;
import com.mark.knowledge.rag.dto.DocumentDeleteResponse;
import com.mark.knowledge.rag.dto.ErrorResponse;
import com.mark.knowledge.rag.dto.ProcessedFileDTO;
import com.mark.knowledge.rag.service.BatchUploadService;
import com.mark.knowledge.rag.service.DocumentAdminService;
import com.mark.knowledge.rag.service.MultiFormatDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    @Autowired
    private DocumentAdminService documentAdminService;
    @Autowired
    private MultiFormatDocumentService multiFormatDocumentService;
    @Autowired
    private BatchUploadService batchUploadService;

    /**
     * 上传并处理文档（支持多格式文件）
     *
     * @param file 文档文件
     * @return 处理结果
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        String userName = getCurrentUsername();
        log.info("收到文件上传请求: 用户={}, 文件名={}", userName, file.getOriginalFilename());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("无效文件", "文件为空"));
        }

        try {
            Result<ProcessedFileDTO> result = multiFormatDocumentService.processMultiFormatFile(file, userName);

            if (result.getCode() == 1 && result.getData() != null && result.getData().isSuccess()) {
                ProcessedFileDTO dto = result.getData();
                Map<String, Object> response = new HashMap<>();
                response.put("filename", dto.getOriginalFilename());
                response.put("message", dto.getMessage());
                response.put("embeddingCount", dto.getEmbeddingCount());
                response.put("textContent", dto.getTextContent());
                return ResponseEntity.ok(response);
            } else {
                String errorMsg = result.getData() != null ? result.getData().getErrorMessage() : result.getMsg();
                return ResponseEntity.badRequest().body(new ErrorResponse("文件处理失败", errorMsg));
            }

        } catch (Exception e) {
            log.error("多格式文件处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("处理失败", e.getMessage()));
        }
    }

    /**
     * 获取批量上传任务状态
     *
     * @param taskId 任务ID
     * @return 任务状态信息
     */
    @GetMapping("/batch-upload/{taskId}")
    public Result<BatchUploadTaskStatusDTO> getBatchUploadStatus(@PathVariable String taskId) {
        BatchUploadTaskStatusDTO status = batchUploadService.getTaskStatus(taskId);
        return Result.success(status);
    }

    /**
     * 批量上传文档接口（支持多格式文件，最多50个文件）
     *
     * @param files 文件列表
     * @param knowledgeBase 知识库标识
     * @param category 文档分类
     * @param tags 标签信息（逗号分隔）
     * @return 批量上传任务信息
     */
    @PostMapping(value = "/batch-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> batchUploadDocuments(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "knowledgeBase", required = false) String knowledgeBase,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) String tags) {
        String userId = getCurrentUsername();

        log.info("收到批量上传请求: 用户={}, 文件数={}, 知识库={}", userId, files.size(), knowledgeBase);

        if (files.isEmpty()) {
            return Result.error("文件列表不能为空");
        }

        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(files);
        request.setUserId(userId);
        request.setKnowledgeBase(knowledgeBase != null ? knowledgeBase : "default");
        request.setCategory(category);
        if (tags != null && !tags.trim().isEmpty()) {
            request.setTags(Arrays.asList(tags.split(",")));
        }

        String taskId = batchUploadService.create(request);
        log.info("批量上传任务创建成功: ID={}, 文件数={}", taskId, files.size());
        return Result.success(taskId);
    }

    /**
     * 查看已上传文档列表
     *
     * @return 文档列表
     */
    @GetMapping
    public ResponseEntity<?> listDocuments() {
        try {
            return ResponseEntity.ok(documentAdminService.listDocuments());
        } catch (Exception e) {
            log.error("获取文档列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("查询失败", e.getMessage()));
        }
    }

    /**
     * 删除指定文档
     *
     * @param documentId 文档ID
     * @return 删除结果
     */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<?> deleteDocument(@PathVariable String documentId) {
        try {
            DocumentDeleteResponse response = documentAdminService.deleteByDocumentId(documentId);
            if (response.deletedSegments() == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("未找到文档", "未找到 documentId=" + documentId + " 对应的知识文档"));
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("删除文档失败: {}", documentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("删除失败", e.getMessage()));
        }
    }

    /**
     * 健康检查接口
     *
     * @return 健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("文档服务运行正常");
    }


    /**
     * 获取当前登录用户名
     */
    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && 
            !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return "unknown";
    }

}