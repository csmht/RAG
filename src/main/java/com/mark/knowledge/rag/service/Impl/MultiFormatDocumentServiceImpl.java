package com.mark.knowledge.rag.service.Impl;

import com.mark.knowledge.auth.common.Result;
import com.mark.knowledge.rag.dto.ProcessedContentDTO;
import com.mark.knowledge.rag.dto.ProcessedFileDTO;
import com.mark.knowledge.rag.entity.UploadedFileEntity;
import com.mark.knowledge.rag.repository.UploadedFileRepository;
import com.mark.knowledge.rag.service.DocumentService;
import com.mark.knowledge.rag.service.EmbeddingService;
import com.mark.knowledge.rag.service.MultiFormatDocumentService;
import dev.langchain4j.data.segment.TextSegment;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MultiFormatDocumentServiceImpl implements MultiFormatDocumentService{

    private static final Logger log = LoggerFactory.getLogger(MultiFormatDocumentServiceImpl.class);

    private static final Map<String, Set<String>> SUPPORTED_FORMATS = Map.of(
            "PDF", Set.of("pdf"),
            "TEXT", Set.of("txt", "md"),
            "DOCUMENT", Set.of("doc", "docx")
    );

    private static final Set<String> STORE_ONLY_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "bmp", "xls", "xlsx", "ppt", "pptx"
    );

    private final Path uploadBaseDir;

    private final DocumentService documentService;
    private final EmbeddingService embeddingService;
    private final UploadedFileRepository uploadedFileRepository;

    public MultiFormatDocumentServiceImpl(DocumentService documentService,
                                      EmbeddingService embeddingService,
                                      UploadedFileRepository uploadedFileRepository) {
        this.uploadBaseDir = Paths.get("uploads");
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.uploadedFileRepository = uploadedFileRepository;
    }

    @Transactional
    @Override
    public Result<ProcessedFileDTO> processMultiFormatFile(MultipartFile file, String userId) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                return Result.error("文件名不能为空");
            }

            String extension = getFileExtension(originalFilename).toLowerCase();
            boolean isStoreOnly = STORE_ONLY_EXTENSIONS.contains(extension);

            String fileType = detectFileType(extension, isStoreOnly);
            if (fileType == null) {
                return Result.error("不支持的文件类型: " + originalFilename);
            }

            String contentType = getContentType(fileType);
            Path savedFilePath = saveOriginalFile(file, userId, fileType, extension);

            ProcessedContentDTO processedContentDTO = processFileContent(
                    file.getInputStream(), fileType, originalFilename, isStoreOnly);

            ProcessedFileDTO dto = new ProcessedFileDTO();
            dto.setOriginalFilename(originalFilename);
            dto.setFilePath(savedFilePath.toString());

            List<TextSegment> segments = null;
            int embeddingCount = 0;

            if (!isStoreOnly && processedContentDTO.isSuccess() && processedContentDTO.getTextContent() != null
                    && !processedContentDTO.getTextContent().trim().isEmpty()) {
                String safeText = sanitizeTextBeforeEmbedding(processedContentDTO.getTextContent());
                if (safeText == null || safeText.trim().isEmpty()) {
                    log.warn("文本内容无效，跳过向量化: 文件={}", originalFilename);
                    dto.setSuccess(false);
                    dto.setMessage("文本内容处理后为空，跳过索引");
                    dto.setEmbeddingCount(0);
                    try {
                        saveFileInfoToDatabase(userId, originalFilename, fileType, extension, savedFilePath, processedContentDTO, null, "FAILED");
                    } catch (Exception e) {
                        log.error("保存文件信息到数据库失败: 用户={}, 文件名={}", userId, originalFilename);
                    }
                    return Result.success(dto);
                }
                segments = documentService.processDocumentContent(safeText, originalFilename);
                embeddingCount = embeddingService.storeSegments(segments);
                processedContentDTO.setEmbeddingCount(embeddingCount);
            }

            log.info("文件处理完成: 类型={}, 用户={}, 文件名={}, 存储={}, 向量数={}",
                    fileType, userId, originalFilename, isStoreOnly, embeddingCount);

            dto.setSuccess(isStoreOnly || processedContentDTO.isSuccess());
            dto.setMessage(isStoreOnly ? "文件已保存，不进行内容处理" : processedContentDTO.getMessage());
            dto.setEmbeddingCount(embeddingCount > 0 ? embeddingCount : (processedContentDTO.getEmbeddingCount() != null ? processedContentDTO.getEmbeddingCount() : 0));
            dto.setTextContent(processedContentDTO.getTextContent());
            dto.setMetadata(processedContentDTO.getMetadata());

            if (isStoreOnly) {
                log.info("非索引文件仅保存本地: 用户={}, 文件名={}, 路径={}",
                        userId, originalFilename, savedFilePath);
                return Result.success(dto);
            }

            try {
                saveFileInfoToDatabase(userId, originalFilename, fileType, extension, savedFilePath, processedContentDTO, segments,
                        "COMPLETED");
            } catch (Exception e) {
                log.error("保存文件信息到数据库失败（影响后续去重）: 用户={}, 文件名={}, 错误={}",
                        userId, originalFilename, e.getMessage());
                throw new IllegalStateException("文件元数据保存失败，请重试: " + originalFilename, e);
            }

            return Result.success(dto);

        } catch (Exception e) {
            log.error("文件处理失败", e);
            ProcessedFileDTO dto = new ProcessedFileDTO();
            dto.setSuccess(false);
            dto.setErrorMessage(e.getMessage());
            return Result.success(dto);
        }
    }
    /**
     * 保存文件信息到数据库
     */
    private void saveFileInfoToDatabase(String userId, String originalFilename, String fileType,
                                        String extension, Path savedFilePath, ProcessedContentDTO processedContentDTO,
                                        List<TextSegment> segments, String status) {
        try {
            UploadedFileEntity uploadedFile = new UploadedFileEntity();
            uploadedFile.setUserId(userId);
            uploadedFile.setFilename(savedFilePath.getFileName().toString());
            uploadedFile.setOriginalFilename(originalFilename);
            uploadedFile.setFileType(fileType);
            uploadedFile.setFilePath(savedFilePath.toString());
            uploadedFile.setFileSize(Files.size(savedFilePath));
            uploadedFile.setContentType(getContentType(fileType));
            uploadedFile.setEmbeddingCount(segments != null ? segments.size() : (processedContentDTO.getEmbeddingCount() != null ? processedContentDTO.getEmbeddingCount() : 0));
            uploadedFile.setProcessedText(processedContentDTO.getTextContent());
            uploadedFile.setProcessedTime(LocalDateTime.now());
            uploadedFile.setStatus(status);
            uploadedFile.setFileHash(calculateFileHashFromFile(savedFilePath));

            uploadedFileRepository.save(uploadedFile);

            log.debug("文件信息已保存到数据库: 用户={}, 文件名={}", userId, originalFilename);
        } catch (Exception e) {
            log.error("保存文件信息到数据库失败: 用户={}, 文件名={}", userId, originalFilename, e);
            throw new RuntimeException("文件元数据保存失败: " + e.getMessage(), e);
        }
    }

    private String detectFileType(String extension, boolean isStoreOnly) {
        if (isStoreOnly) {
            return "FILE";
        }
        for (Map.Entry<String, Set<String>> entry : SUPPORTED_FORMATS.entrySet()) {
            if (entry.getValue().contains(extension)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private Path saveOriginalFile(MultipartFile file, String userId, String fileType, String extension) throws IOException {
        Path userDir = uploadBaseDir.resolve(userId);
        Files.createDirectories(userDir);
        String uniqueFilename = generateUniqueFilename(fileType, extension);
        Path filePath = userDir.resolve(uniqueFilename);
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
        return filePath;
    }

    private ProcessedContentDTO processFileContent(InputStream inputStream, String fileType, String filename, boolean isStoreOnly) {
        if (isStoreOnly) {
            return new ProcessedContentDTO(false, "仅保存文件，不进行内容处理", null, null);
        }
        try {
            return switch (fileType) {
                case "PDF", "TEXT" -> processTextBasedFile(inputStream, filename);
                case "DOCUMENT" -> processOfficeDocument(inputStream, filename);
                default -> new ProcessedContentDTO(false, "未知文件类型", null, null);
            };
        } catch (Exception e) {
            log.error("文件内容处理失败", e);
            return new ProcessedContentDTO(false, "内容处理失败: " + e.getMessage(), null, null);
        }
    }

    private ProcessedContentDTO processTextBasedFile(InputStream inputStream, String filename) {
        try {
            DocumentService.ProcessedDocument processed = documentService.processDocument(inputStream, filename);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("processedAt", LocalDateTime.now());
            metadata.put("segmentCount", processed.segments().size());
            String textContent = extractPreviewText(processed.segments());
            return new ProcessedContentDTO(true, "文本文件处理完成", textContent, metadata);
        } catch (Exception e) {
            return new ProcessedContentDTO(false, "文本文件处理失败: " + e.getMessage(), null, null);
        }
    }

    private ProcessedContentDTO processOfficeDocument(InputStream inputStream, String filename) {
        String extractedText;
        try {
            extractedText = extractTextFromOfficeDocument(inputStream, filename);
        } catch (Exception e) {
            log.error("Office文档内容提取失败: 文件={}, 错误={}", filename, e.getMessage());
            return new ProcessedContentDTO(false, "Office文档内容提取失败: " + e.getMessage(), null, null);
        }

        if (extractedText.trim().isEmpty()) {
            return new ProcessedContentDTO(false, "Office文档中未提取到文字内容", null, null);
        }

        if (isOnlyWhitespaceOrGarbage(extractedText)) {
            log.warn("Office文档内容无效或仅含垃圾字符: 文件={}", filename);
            return new ProcessedContentDTO(false, "Office文档内容为空或无效", null, null);
        }

        try {
            DocumentService.ProcessedDocument processed =
                    documentService.processDocument(
                            new ByteArrayInputStream(extractedText.getBytes(StandardCharsets.UTF_8)),
                            filename + ".txt");

            String textContent = extractPreviewText(processed.segments());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("processedAt", LocalDateTime.now());
            metadata.put("contentType", "office");
            metadata.put("segmentCount", processed.segments().size());

            return new ProcessedContentDTO(true, "Office文档处理完成", textContent, metadata);
        } catch (Exception e) {
            return new ProcessedContentDTO(false, "Office文档处理失败: " + e.getMessage(), null, null);
        }
    }

    /**
     * 从图片中提取文字（OCR）
     */
    private String extractTextFromImage(InputStream inputStream) {
        try {
            BufferedImage image = ImageIO.read(inputStream);

            ITesseract tesseract = new Tesseract();
            String tessDataPath = System.getProperty("user.dir") + "/tessdata";
            tesseract.setDatapath(tessDataPath);
            tesseract.setLanguage("chi_sim+eng");

            String extractedText = tesseract.doOCR(image);

            if (extractedText != null && !extractedText.trim().isEmpty()) {
                return extractedText.trim();
            } else {
                throw new IOException("图片中未检测到可读取的文字内容");
            }
        } catch (IOException | net.sourceforge.tess4j.TesseractException e) {
            log.warn("OCR提取文字失败: {}", e.getMessage());
            throw new RuntimeException("图片文字提取失败: " + e.getMessage());
        }
    }

    /**
     * 从Office文档中提取文字
     */
    private String extractTextFromOfficeDocument(InputStream inputStream, String filename) {
        try {
            String extension = getFileExtension(filename).toLowerCase();
            String text;

            switch (extension) {
                case "doc":
                    try (POIFSFileSystem fs = new POIFSFileSystem(inputStream)) {
                        HWPFDocument document = new HWPFDocument(fs.getRoot());
                        WordExtractor extractor = new WordExtractor(document);
                        text = extractor.getText();
                        extractor.close();
                        document.close();
                        return text;
                    }
                case "docx":
                    try (XWPFDocument document = new XWPFDocument(inputStream)) {
                        XWPFWordExtractor extractor = new XWPFWordExtractor(document);
                        text = extractor.getText();
                        extractor.close();
                        return text;
                    }
                case "xls":
                    try (HSSFWorkbook workbook = new HSSFWorkbook(inputStream)) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(i);
                            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                        sb.append(cell.getStringCellValue()).append(" ");
                                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                                        sb.append(cell.getNumericCellValue()).append(" ");
                                    }
                                }
                                sb.append("\n");
                            }
                        }
                        return sb.toString();
                    }
                case "xlsx":
                    try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(i);
                            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                                    switch (cell.getCellType()) {
                                        case STRING:
                                            sb.append(cell.getStringCellValue()).append(" ");
                                            break;
                                        case NUMERIC:
                                            sb.append(cell.getNumericCellValue()).append(" ");
                                            break;
                                        case BOOLEAN:
                                            sb.append(cell.getBooleanCellValue()).append(" ");
                                            break;
                                        default:
                                            break;
                                    }
                                }
                                sb.append("\n");
                            }
                        }
                        return sb.toString();
                    }
                case "ppt":
                case "pptx":
                    try (POITextExtractor extractor = ExtractorFactory.createExtractor(inputStream)) {
                        text = extractor.getText();
                        return text != null ? text : "";
                    }
                default:
                    throw new RuntimeException("不支持的Office文档格式: " + extension);
            }
        } catch (Exception e) {
            log.warn("Office文档处理失败: {}", e.getMessage());
            throw new RuntimeException("Office文档处理失败: " + e.getMessage());
        }
    }

    /**
     * 提取预览文本
     */
    private String extractPreviewText(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }

        // 取前3个片段作为预览
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < Math.min(3, segments.size()); i++) {
            preview.append(segments.get(i).text()).append("\n\n");
        }

        return preview.toString();
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    /**
     * 获取内容类型
     */
    private String getContentType(String fileType) {
        return switch (fileType) {
            case "PDF" -> "application/pdf";
            case "TEXT" -> "text/plain";
            case "DOCUMENT" -> "application/msword";
            case "FILE" -> "application/octet-stream";
            default -> "application/octet-stream";
        };
    }

    /**
     * 从文件路径计算文件哈希值
     */
    private String calculateFileHashFromFile(Path filePath) {
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("计算文件哈希值失败: {}", filePath, e);
            return null;
        }
    }

    /**
     * 生成唯一文件名
     */
    private String generateUniqueFilename(String fileType, String extension) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String random = UUID.randomUUID().toString().substring(0, 8);
        return fileType.toLowerCase() + "_" + timestamp + "_" + random + "." + extension;
    }

    /**
     * 检测文本是否仅为空白或垃圾内容
     */
    private boolean isOnlyWhitespaceOrGarbage(String text) {
        if (text == null) {
            return true;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("处理失败") || lower.contains("error") || lower.contains("exception") ||
            lower.contains("未提取") || lower.contains("失败") || lower.contains("错误")) {
            return true;
        }
        long letterCount = text.chars().filter(Character::isLetter).count();
        if (letterCount > 0 && (double) letterCount / text.length() < 0.2) {
            return true;
        }
        return false;
    }

    /**
     * 向量化前清理文本，防止失败信息被当正文处理
     */
    private String sanitizeTextBeforeEmbedding(String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase();
        if (lower.contains("处理失败") || lower.contains("error") || lower.contains("exception") ||
            lower.contains("未提取") || lower.contains("失败") || lower.contains("错误") ||
            lower.contains("ocr") || lower.contains("extract")) {
            log.warn("检测到错误信息混入正文，拒绝向量化");
            return null;
        }
        return text;
    }
}
