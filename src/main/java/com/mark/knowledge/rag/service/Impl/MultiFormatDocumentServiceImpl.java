package com.mark.knowledge.rag.service.Impl;

import com.mark.knowledge.rag.common.MultiFormatProcessResult;
import com.mark.knowledge.rag.dto.FileTypeDTO;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class MultiFormatDocumentServiceImpl implements MultiFormatDocumentService{

    private static final Logger log = LoggerFactory.getLogger(MultiFormatDocumentServiceImpl.class);

    // 支持的文件类型映射
    private static final Map<String, Set<String>> SUPPORTED_FORMATS = Map.of(
            "PDF", Set.of("pdf"),
            "TEXT", Set.of("txt", "md"),
            "IMAGE", Set.of("jpg", "jpeg", "png", "bmp"),
            "DOCUMENT", Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx")
    );

    // 文件上传目录
    private final Path uploadBaseDir = Paths.get("uploads");

    private final DocumentService documentService;
    private final EmbeddingService embeddingService;
    private final UploadedFileRepository uploadedFileRepository;

    public MultiFormatDocumentServiceImpl(DocumentService documentService,
                                      EmbeddingService embeddingService,
                                      UploadedFileRepository uploadedFileRepository) {
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.uploadedFileRepository = uploadedFileRepository;
    }

    /**
     * 处理多格式文件上传
     */
    @Transactional
    @Override
    public MultiFormatProcessResult processMultiFormatFile(MultipartFile file, String userId) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                return new MultiFormatProcessResult(false, "文件名不能为空", null);
            }

            // 检测文件类型
            FileTypeDTO fileType = detectFileType(originalFilename);
            if (fileType == null) {
                return new MultiFormatProcessResult(false, "不支持的文件类型: " + originalFilename, null);
            }

            // 保存原始文件
            Path savedFilePath = saveOriginalFile(file, userId, fileType);

            // 根据文件类型处理内容
            ProcessedContentDTO processedContentDTO = processFileContent(file.getInputStream(), fileType, originalFilename);

            // 如果处理成功且是文本内容，进行向量化
            List<TextSegment> segments = null;
            if (processedContentDTO.isSuccess() && processedContentDTO.getTextContent() != null) {
                segments = documentService.processDocumentContent(processedContentDTO.getTextContent(), originalFilename);
                int embeddingCount = embeddingService.storeSegments(segments);
                processedContentDTO.setEmbeddingCount(embeddingCount);
            }

            log.info("多格式文件处理成功: 类型={}, 用户={}, 文件名={}",
                    fileType.getType(), userId, originalFilename);

            // 保存文件信息到数据库
            saveFileInfoToDatabase(userId, originalFilename, fileType, savedFilePath, processedContentDTO, segments);

            return new MultiFormatProcessResult(true, "文件处理成功",
                    new ProcessedFileDTO(originalFilename, fileType, savedFilePath.toString(), processedContentDTO, segments));

        } catch (Exception e) {
            log.error("多格式文件处理失败", e);
            return new MultiFormatProcessResult(false, "文件处理失败: " + e.getMessage(), null);
        }
    }
    /**
     * 保存文件信息到数据库
     */
    private void saveFileInfoToDatabase(String userId, String originalFilename, FileTypeDTO fileType,
                                        Path savedFilePath, ProcessedContentDTO processedContentDTO,
                                        List<TextSegment> segments) {
        try {
            UploadedFileEntity uploadedFile = new UploadedFileEntity();
            uploadedFile.setUserId(userId);
            uploadedFile.setFilename(savedFilePath.getFileName().toString());
            uploadedFile.setOriginalFilename(originalFilename);
            uploadedFile.setFileType(fileType.getType());
            uploadedFile.setFilePath(savedFilePath.toString());
            uploadedFile.setFileSize(Files.size(savedFilePath));
            uploadedFile.setContentType(fileType.getContentType());
            uploadedFile.setEmbeddingCount(segments != null ? segments.size() : processedContentDTO.getEmbeddingCount());
            uploadedFile.setProcessedText(processedContentDTO.getTextContent());
            uploadedFile.setProcessedTime(LocalDateTime.now());
            uploadedFile.setStatus("COMPLETED");
            uploadedFile.setFileHash(calculateFileHashFromFile(savedFilePath));

            uploadedFileRepository.save(uploadedFile);

            log.debug("文件信息已保存到数据库: 用户={}, 文件名={}", userId, originalFilename);
        } catch (Exception e) {
            log.error("保存文件信息到数据库失败: 用户={}, 文件名={}", userId, originalFilename, e);
        }
    }

    /**
     * 检测文件类型
     */
    private FileTypeDTO detectFileType(String filename) {
        String extension = getFileExtension(filename).toLowerCase();

        for (Map.Entry<String, Set<String>> entry : SUPPORTED_FORMATS.entrySet()) {
            if (entry.getValue().contains(extension)) {
                return new FileTypeDTO(entry.getKey(), extension, getContentType(entry.getKey()));
            }
        }

        return null;
    }

    /**
     * 保存原始文件
     */
    private Path saveOriginalFile(MultipartFile file, String userId, FileTypeDTO fileType) throws IOException {
        // 创建用户目录
        Path userDir = uploadBaseDir.resolve(userId);
        Files.createDirectories(userDir);

        // 生成唯一文件名
        String uniqueFilename = generateUniqueFilename(fileType, file.getOriginalFilename());
        Path filePath = userDir.resolve(uniqueFilename);

        // 保存文件
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        }

        return filePath;
    }

    /**
     * 处理文件内容
     */
    private ProcessedContentDTO processFileContent(InputStream inputStream, FileTypeDTO fileType, String filename) {
        try {
            return switch (fileType.getType()) {
                case "PDF", "TEXT" -> processTextBasedFile(inputStream, filename);
                case "IMAGE" -> processImageFile(inputStream);
                case "DOCUMENT" -> processOfficeDocument(inputStream, filename);
                default -> new ProcessedContentDTO(false, "未知文件类型", null, null);

            };
        } catch (Exception e) {
            log.error("文件内容处理失败", e);
            return new ProcessedContentDTO(false, "内容处理失败: " + e.getMessage(), null, null);
        }
    }

    /**
     * 处理文本类文件（PDF、TXT）
     */
    private ProcessedContentDTO processTextBasedFile(InputStream inputStream, String filename) {
        try {
            // 使用现有的DocumentService处理
            DocumentService.ProcessedDocument processed = documentService.processDocument(inputStream, filename);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("processedAt", LocalDateTime.now());
            metadata.put("segmentCount", processed.segments().size());

            // 提取文本内容（用于显示）
            String textContent = extractPreviewText(processed.segments());

            return new ProcessedContentDTO(true, "文本文件处理完成", textContent, metadata);
        } catch (Exception e) {
            return new ProcessedContentDTO(false, "文本文件处理失败: " + e.getMessage(), null, null);
        }
    }

    /**
     * 处理图片文件
     */
    private ProcessedContentDTO processImageFile(InputStream inputStream) {
        // 集成OCR服务提取图片文字
        String extractedText = extractTextFromImage(inputStream);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("processedAt", LocalDateTime.now());
        metadata.put("contentType", "image");
        metadata.put("ocrApplied", true);

        return new ProcessedContentDTO(true, "图片处理完成", extractedText, metadata);
    }

    /**
     * 处理Office文档
     */
    private ProcessedContentDTO processOfficeDocument(InputStream inputStream, String filename) {
        // 集成Office文档解析库
        String extractedText = extractTextFromOfficeDocument(inputStream, filename);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("processedAt", LocalDateTime.now());
        metadata.put("contentType", "office");

        return new ProcessedContentDTO(true, "Office文档处理完成", extractedText, metadata);
    }

    /**
     * 从图片中提取文字（OCR）
     */
    private String extractTextFromImage(InputStream inputStream) {
        try {
            // 将输入流转为BufferedImage
            BufferedImage image = ImageIO.read(inputStream);

            // 初始化Tesseract实例
            ITesseract tesseract = new Tesseract();

            // 设置Tesseract数据文件路径（需要安装Tesseract并配置tessdata）
            String tessDataPath = System.getProperty("user.dir") + "/tessdata"; // 默认路径
            tesseract.setDatapath(tessDataPath);

            // 设置语言（中文+英文）
            tesseract.setLanguage("chi_sim+eng");

            // 执行OCR识别
            String extractedText = tesseract.doOCR(image);

            // 清理提取的文本
            if (extractedText != null && !extractedText.trim().isEmpty()) {
                return extractedText.trim();
            } else {
                // 如果OCR没有提取到文字，返回一个描述性的文本
                return "此图像文件中未检测到可读取的文字内容";
            }
        } catch (Exception e) {
            log.warn("OCR处理失败: {}", e.getMessage());
            // 如果OCR失败，至少返回一个描述性文本
            return "图像文件，OCR处理失败: " + e.getMessage();
        }
    }

    /**
     * 从Office文档中提取文字
     */
    private String extractTextFromOfficeDocument(InputStream inputStream, String filename) {
        try {
            String extension = getFileExtension(filename).toLowerCase();

            switch (extension) {
                case "doc":
                    // 处理旧版Word文档(.doc)
                    try (POIFSFileSystem fs = new POIFSFileSystem(inputStream)) {
                        HSSFWorkbook workbook = new HSSFWorkbook(fs);
                        WordExtractor extractor = new WordExtractor(workbook.getDirectory());
                        String text = extractor.getText();
                        extractor.close();
                        return text != null ? text : "";
                    }
                case "docx":
                    // 处理新版Word文档(.docx)
                    try (XWPFDocument document = new XWPFDocument(inputStream)) {
                        XWPFWordExtractor extractor = new XWPFWordExtractor(document);
                        String text = extractor.getText();
                        extractor.close();
                        return text != null ? text : "";
                    }
                case "xls":
                    // 处理旧版Excel文档(.xls)
                    try (HSSFWorkbook workbook = new HSSFWorkbook(inputStream)) {
                        StringBuilder text = new StringBuilder();
                        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(i);
                            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                        text.append(cell.getStringCellValue()).append(" ");
                                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                                        text.append(cell.getNumericCellValue()).append(" ");
                                    }
                                }
                                text.append("\n");
                            }
                        }
                        return text.toString();
                    }
                case "xlsx":
                    // 处理新版Excel文档(.xlsx)
                    try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
                        StringBuilder text = new StringBuilder();
                        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(i);
                            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                                    switch (cell.getCellType()) {
                                        case STRING:
                                            text.append(cell.getStringCellValue()).append(" ");
                                            break;
                                        case NUMERIC:
                                            text.append(cell.getNumericCellValue()).append(" ");
                                            break;
                                        case BOOLEAN:
                                            text.append(cell.getBooleanCellValue()).append(" ");
                                            break;
                                        default:
                                            break;
                                    }
                                }
                                text.append("\n");
                            }
                        }
                        return text.toString();
                    }
                case "ppt":
                case "pptx":
                    // 对于PowerPoint，我们可以提取文本但忽略格式
                    try (POITextExtractor extractor = ExtractorFactory.createExtractor(inputStream)) {
                        String text = extractor.getText();
                        return text != null ? text : "";
                    }
                default:
                    return "不支持的Office文档格式";
            }
        } catch (Exception e) {
            log.warn("Office文档处理失败: {}", e.getMessage());
            return "文档处理失败: " + e.getMessage();
        }
    }

    /**
     * 提取预览文本
     */
    private String extractPreviewText(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return "";
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
            case "IMAGE" -> "image/*";
            case "DOCUMENT" -> "application/vnd.openxmlformats-officedocument.*";
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
    private String generateUniqueFilename(FileTypeDTO fileType, String originalFilename) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String random = UUID.randomUUID().toString().substring(0, 8);
        String extension = getFileExtension(originalFilename);
        return fileType.getType().toLowerCase() + "_" + timestamp + "_" + random + "." + extension;
    }
}
