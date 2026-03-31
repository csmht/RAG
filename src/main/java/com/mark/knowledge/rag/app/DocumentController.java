package com.mark.knowledge.rag.app;


import com.mark.knowledge.rag.dto.DocumentDeleteResponse;
import com.mark.knowledge.rag.dto.DocumentResponse;
import com.mark.knowledge.rag.dto.ErrorResponse;
import com.mark.knowledge.rag.service.DocumentAdminService;
import com.mark.knowledge.rag.service.DocumentService;
import com.mark.knowledge.rag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;

/**
 * 文档上传和管理控制器
 *
 * @author mark
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;
    private final EmbeddingService embeddingService;
    private final DocumentAdminService documentAdminService;

    public DocumentController(
            DocumentService documentService,
            EmbeddingService embeddingService,
            DocumentAdminService documentAdminService) {
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.documentAdminService = documentAdminService;
    }

    /**
     * 上传并处理文档
     *
     * @param file 文档文件（PDF 或 TXT 格式）
     * @return 处理结果
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        log.info("收到文档上传请求: {}", file.getOriginalFilename());

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("无效文件", "文件为空"));
            }

            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("无效文件", "文件名缺失"));
            }

            String lowerFilename = filename.toLowerCase(Locale.ROOT);
            if (!lowerFilename.endsWith(".pdf") && !lowerFilename.endsWith(".txt")) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("不支持的文件类型", "仅支持 PDF 和 TXT 文件"));
            }

            try (InputStream inputStream = file.getInputStream()) {
                DocumentService.ProcessedDocument processed = documentService.processDocument(
                    inputStream,
                    filename
                );

                int embeddingCount = embeddingService.storeSegments(processed.segments());

                log.info("文档处理成功: {} ({} 个片段)", filename, embeddingCount);

                return ResponseEntity.ok(new DocumentResponse(
                    processed.documentId(),
                    filename,
                    "文档处理成功",
                    embeddingCount
                ));
            }

        } catch (Exception e) {
            log.error("文档处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("处理失败", e.getMessage()));
        }
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
}
