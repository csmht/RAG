package com.mark.knowledge.rag.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档服务 - 文档处理和分块*
 * 服务职责：
 * - 处理文档输入（解析、清洗、增强和分块）
 * - 支持多种格式（PDF、TXT）
 * - 将文档切分为适合中文嵌入和检索的文本块
 * - 保留标题、分类、时间等元数据以便溯源
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Pattern FULL_DATE_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{4})[年/.-](\\d{1,2})[月/.-](\\d{1,2})日?(?!\\d)"
    );
    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{4})[年/.-](\\d{1,2})月?(?!\\d)"
    );
    private static final Pattern LATIN_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,30}");
    private static final Pattern HAN_SEQUENCE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,24}");
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CHINESE_SECTION_HEADING_PATTERN = Pattern.compile("^([一二三四五六七八九十]+、|（[一二三四五六七八九十]+）)\\s*(.+)$");
    private static final Pattern NUMERIC_SECTION_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+){0,2}[、.])\\s*(.+)$");
    private static final Pattern NUMERIC_SECTION_HEADING_PAREN_PATTERN = Pattern.compile("^（?\\d+）\\s*(.+)$");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^(?:[-*•]|\\d+[.)]|[一二三四五六七八九十]+、|（\\d+）)\\s+.+$");

    private static final Set<String> KEYWORD_STOP_WORDS = Set.of(
        "我们", "你们", "他们", "她们", "是否", "已经", "进行", "可以", "需要", "因为", "所以",
        "为了", "如果", "或者", "以及", "其中", "通过", "对于", "相关", "这个", "那个", "这些",
        "那些", "一种", "一个", "一些", "没有", "并且", "然后", "内容", "文档", "文件", "文本",
        "部分", "问题", "方法", "处理", "系统", "能力", "模型", "数据", "信息", "进行中",
        "title", "document", "content", "with", "from", "that", "this", "have", "will",
        "into", "about", "there", "their", "your", "were", "been"
    );

    @Value("${rag.chunk-size:320}")
    private int chunkSize;

    @Value("${rag.chunk-min-size:250}")
    private int chunkMinSize;

    @Value("${rag.chunk-max-size:350}")
    private int chunkMaxSize;

    @Value("${rag.chunk-overlap:40}")
    private int chunkOverlap;

    @Value("${rag.min-text-length:80}")
    private int minTextLength;

    @Value("${rag.keyword-count:6}")
    private int keywordCount;

    @Value("${rag.chunk-title-max-length:24}")
    private int chunkTitleMaxLength;


    /**
     * 处理输入流中的文档
     *
     * @param inputStream 文档输入流
     * @param filename 文件名（含扩展名）
     * @return 处理后的文档（包含文本块）
     */
    public ProcessedDocument processDocument(InputStream inputStream, String filename) {
        long startTime = System.currentTimeMillis();
        ChunkSettings chunkSettings = resolveChunkSettings();

        log.info("==========================================");
        log.info("文档处理开始");
        log.info("  文件名: {}", filename);
        log.info("  分块范围: {}~{} 字符", chunkSettings.minSize(), chunkSettings.maxSize());
        log.info("  目标分块大小: {}", chunkSettings.targetSize());
        log.info("  重叠大小: {}", chunkOverlap);
        log.info("==========================================");

        String documentId = UUID.randomUUID().toString();

        try {
            log.info("📖 [步骤1/4] 解析文档: {}", filename);
            long parseStart = System.currentTimeMillis();

            String rawContent;
            if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                rawContent = parsePdf(inputStream);
                log.info("✓ PDF解析成功 ({} 字符)", rawContent.length());
            } else {
                rawContent = parseText(inputStream);
                log.info("✓ 文本解析成功 ({} 字符)", rawContent.length());
            }

            long parseTime = System.currentTimeMillis() - parseStart;
            log.info("  解析完成，耗时: {} ms", parseTime);

            if (rawContent.isBlank()) {
                throw new IllegalArgumentException("文档内容为空");
            }

            log.info("🧹 [步骤2/4] 清洗文本并提取元数据...");
            long cleanStart = System.currentTimeMillis();
            String cleanedContent = cleanText(rawContent);
            if (cleanedContent.isBlank()) {
                throw new IllegalArgumentException("文本清洗后内容为空");
            }

            DocumentProfile profile = buildDocumentProfile(filename, cleanedContent);
            long cleanTime = System.currentTimeMillis() - cleanStart;
            log.info("✓ 文本清洗完成，耗时: {} ms", cleanTime);
            log.info("  清洗前长度: {} 字符", rawContent.length());
            log.info("  清洗后长度: {} 字符", profile.bodyText().length());
            log.info("  标题: {}", profile.title());
            log.info("  分类: {}", profile.category());
            log.info("  时间: {}", profile.documentTime());

            log.info("✂️  [步骤3/4] 切分文本块并做增强...");
            long splitStart = System.currentTimeMillis();
            ChunkBuildResult chunkBuildResult = splitText(profile, filename, documentId, chunkSettings);
            List<TextSegment> segments = chunkBuildResult.segments();
            long splitTime = System.currentTimeMillis() - splitStart;

            log.info("✓ 文档已切分为 {} 个文本块，耗时: {} ms", segments.size(), splitTime);
            log.info("  过滤短文本: {} 个", chunkBuildResult.filteredShortCount());
            log.info("  过滤重复文本: {} 个", chunkBuildResult.filteredDuplicateCount());
            log.info("  平均块原文大小: {} 字符",
                profile.bodyText().length() / Math.max(1, segments.size()));

            log.info("📦 [步骤4/4] 创建处理后的文档结果");
            ProcessedDocument result = new ProcessedDocument(documentId, filename, segments);

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("==========================================");
            log.info("文档处理完成");
            log.info("  文档ID: {}", documentId);
            log.info("  文本块总数: {}", segments.size());
            log.info("  总耗时: {} ms", totalTime);
            log.info("==========================================");

            return result;

        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("==========================================");
            log.error("文档处理失败");
            log.error("  文件名: {}", filename);
            log.error("  已用时间: {} ms", totalTime);
            log.error("  错误: {}", e.getMessage(), e);
            log.error("==========================================");
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理纯文本内容（用于多格式文件服务）
     *
     * @param textContent 文本内容
     * @param filename 文件名
     * @return 处理后的文本片段
     */
    public List<dev.langchain4j.data.segment.TextSegment> processDocumentContent(String textContent, String filename) {
        if (textContent == null || textContent.trim().isEmpty()) {
            return List.of();
        }

        try {
            // 创建临时文本片段
            List<TextSegment> segments = new ArrayList<>();
            String documentId = UUID.randomUUID().toString();

            // 简单的文本分块（可以根据需要改进）
            String[] lines = textContent.split("\\n\\n+");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.length() >= minTextLength) {
                    Metadata metadata = new Metadata();
                    metadata.put("documentId", documentId);
                    metadata.put("filename", filename);
                    metadata.put("chunk_index", String.valueOf(i));

                    segments.add(TextSegment.from(line, metadata));
                }
            }

            return segments;
        } catch (Exception e) {
            log.error("处理文本内容失败", e);
            return List.of();
        }
    }

    private ChunkBuildResult splitText(
            DocumentProfile profile,
            String filename,
            String documentId,
            ChunkSettings chunkSettings) {
        log.debug("开始文本切分过程...");
        log.debug("  文本总长度: {} 字符", profile.bodyText().length());

        DeduplicationResult unitDeduplication = deduplicateUnits(splitIntoUnits(profile.bodyText(), chunkSettings));
        List<ChunkCandidate> chunks = mergeShortChunks(assembleChunks(unitDeduplication.units(), chunkSettings), chunkSettings);

        List<TextSegment> segments = new ArrayList<>();
        Set<String> seenChunks = new HashSet<>();
        int filteredShortCount = 0;
        int filteredDuplicateCount = unitDeduplication.filteredDuplicateCount();
        int chunkIndex = 0;

        for (ChunkCandidate chunk : chunks) {
            String rawChunk = chunk.text();
            String normalizedChunk = normalizeForDedup(rawChunk);
            if (normalizedChunk.length() < minTextLength) {
                filteredShortCount++;
                continue;
            }

            if (!seenChunks.add(normalizedChunk)) {
                filteredDuplicateCount++;
                continue;
            }

            List<String> chunkKeywords = resolveChunkKeywords(profile, rawChunk);
            String chunkTitle = resolveChunkTitle(profile, chunk, chunkKeywords);
            String enhancedText = buildEnhancedText(chunkTitle, rawChunk, chunkKeywords);

            segments.add(createSegment(
                enhancedText,
                rawChunk,
                filename,
                documentId,
                chunkIndex++,
                normalizedChunk,
                profile,
                chunkKeywords,
                chunkTitle
            ));
        }

        log.debug("文本切分完成: 创建了 {} 个文本块", segments.size());
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("文档清洗后没有足够的有效文本可入库");
        }

        return new ChunkBuildResult(segments, filteredShortCount, filteredDuplicateCount);
    }

    /**
     * 对结构化单元做去重，保留标题单元，避免章节上下文丢失。
     */
    private DeduplicationResult deduplicateUnits(List<StructuredUnit> units) {
        List<StructuredUnit> uniqueUnits = new ArrayList<>();
        Set<String> seenUnits = new HashSet<>();
        int filteredDuplicateCount = 0;

        for (StructuredUnit unit : units) {
            String normalizedUnit = unit.normalizedText();
            if (normalizedUnit.isBlank()) {
                continue;
            }
            if (unit.unitType() != UnitType.HEADING && !seenUnits.add(normalizedUnit)) {
                filteredDuplicateCount++;
                continue;
            }
            uniqueUnits.add(unit);
        }

        return new DeduplicationResult(uniqueUnits, filteredDuplicateCount);
    }

    /**
     * 将正文拆为结构化单元，并在拆分阶段识别标题、列表与章节归属。
     */
    private List<StructuredUnit> splitIntoUnits(String text, ChunkSettings chunkSettings) {
        List<String> paragraphs = Arrays.stream(text.split("\\n\\n+"))
            .map(String::trim)
            .filter(paragraph -> !paragraph.isBlank())
            .collect(Collectors.toList());
        log.debug("  发现 {} 个段落", paragraphs.size());

        List<StructuredUnit> units = new ArrayList<>();
        String currentSectionTitle = "";
        int currentHeadingLevel = 0;

        for (String paragraph : paragraphs) {
            List<String> lines = Arrays.stream(paragraph.split("\\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();

            if (!lines.isEmpty()) {
                HeadingMatch firstLineHeading = matchHeading(lines.getFirst());
                if (firstLineHeading.matched()) {
                    currentSectionTitle = firstLineHeading.title();
                    currentHeadingLevel = firstLineHeading.level();
                    units.add(createUnit(lines.getFirst(), UnitType.HEADING, currentSectionTitle, currentHeadingLevel, false));
                    String remainingText = lines.stream().skip(1).collect(Collectors.joining("\n")).trim();
                    if (!remainingText.isBlank()) {
                        appendParagraphUnit(units, remainingText, currentSectionTitle, currentHeadingLevel, chunkSettings);
                    }
                    continue;
                }
            }

            HeadingMatch headingMatch = matchHeading(paragraph);
            if (headingMatch.matched()) {
                currentSectionTitle = headingMatch.title();
                currentHeadingLevel = headingMatch.level();
                units.add(createUnit(paragraph, UnitType.HEADING, currentSectionTitle, currentHeadingLevel, false));
                continue;
            }

            appendParagraphUnit(units, paragraph, currentSectionTitle, currentHeadingLevel, chunkSettings);
        }
        return units;
    }

    private void appendParagraphUnit(
            List<StructuredUnit> units,
            String paragraph,
            String currentSectionTitle,
            int currentHeadingLevel,
            ChunkSettings chunkSettings) {
        UnitType unitType = isListParagraph(paragraph) ? UnitType.LIST : UnitType.PARAGRAPH;
        if (paragraph.length() <= chunkSettings.maxSize()) {
            units.add(createUnit(paragraph, unitType, currentSectionTitle, currentHeadingLevel, false));
            return;
        }

        if (unitType == UnitType.LIST) {
            units.addAll(splitLongList(paragraph, currentSectionTitle, currentHeadingLevel, chunkSettings));
        } else {
            units.addAll(splitLongParagraph(paragraph, currentSectionTitle, currentHeadingLevel, unitType, chunkSettings));
        }
    }

    /**
     * 按句子边界拆分超长正文，并保留所属章节与单元类型。
     */
    private List<StructuredUnit> splitLongParagraph(
            String paragraph,
            String sectionTitle,
            int headingLevel,
            UnitType unitType,
            ChunkSettings chunkSettings) {
        List<StructuredUnit> units = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[。！？!?；;])");
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            String trimmedSentence = sentence.trim();
            if (trimmedSentence.isBlank()) {
                continue;
            }

            if (trimmedSentence.length() > chunkSettings.maxSize()) {
                if (!current.isEmpty()) {
                    units.add(createUnit(current.toString().trim(), unitType, sectionTitle, headingLevel, false));
                    current.setLength(0);
                }
                units.addAll(sliceLongText(trimmedSentence, sectionTitle, headingLevel, unitType, chunkSettings));
                continue;
            }

            if (current.isEmpty()) {
                current.append(trimmedSentence);
                continue;
            }

            if (current.length() + 1 + trimmedSentence.length() <= chunkSettings.maxSize()) {
                current.append(' ').append(trimmedSentence);
            } else {
                units.add(createUnit(current.toString().trim(), unitType, sectionTitle, headingLevel, false));
                current.setLength(0);
                current.append(trimmedSentence);
            }
        }

        if (!current.isEmpty()) {
            units.add(createUnit(current.toString().trim(), unitType, sectionTitle, headingLevel, false));
        }

        return units;
    }

    /**
     * 优先按列表项拆分超长列表，必要时再回退到通用切片逻辑。
     */
    private List<StructuredUnit> splitLongList(
            String paragraph,
            String sectionTitle,
            int headingLevel,
            ChunkSettings chunkSettings) {
        List<String> listItems = splitListItems(paragraph);
        if (listItems.size() <= 1) {
            return splitLongParagraph(paragraph, sectionTitle, headingLevel, UnitType.LIST, chunkSettings);
        }

        List<StructuredUnit> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String item : listItems) {
            if (item.length() > chunkSettings.maxSize()) {
                if (!current.isEmpty()) {
                    units.add(createUnit(current.toString().trim(), UnitType.LIST, sectionTitle, headingLevel, false));
                    current.setLength(0);
                }
                units.addAll(sliceLongText(item, sectionTitle, headingLevel, UnitType.LIST, chunkSettings));
                continue;
            }

            if (current.isEmpty()) {
                current.append(item);
                continue;
            }

            if (current.length() + 1 + item.length() <= chunkSettings.maxSize()) {
                current.append('\n').append(item);
            } else {
                units.add(createUnit(current.toString().trim(), UnitType.LIST, sectionTitle, headingLevel, false));
                current.setLength(0);
                current.append(item);
            }
        }

        if (!current.isEmpty()) {
            units.add(createUnit(current.toString().trim(), UnitType.LIST, sectionTitle, headingLevel, false));
        }
        return units;
    }

    /**
     * 对超长文本执行带重叠窗口的切片，并尽量在语义边界处截断。
     */
    private List<StructuredUnit> sliceLongText(
            String text,
            String sectionTitle,
            int headingLevel,
            UnitType unitType,
            ChunkSettings chunkSettings) {
        List<StructuredUnit> slices = new ArrayList<>();
        int overlap = Math.max(0, Math.min(chunkOverlap, chunkSettings.minSize() / 2));
        int start = 0;

        while (start < text.length()) {
            int maxEnd = Math.min(start + chunkSettings.maxSize(), text.length());
            int end = maxEnd;

            if (maxEnd < text.length()) {
                int preferredMin = Math.min(start + chunkSettings.minSize(), text.length());
                int boundary = findBoundary(text, preferredMin, maxEnd);
                if (boundary > start) {
                    end = boundary;
                }
            }

            String slice = text.substring(start, end).trim();
            if (!slice.isBlank()) {
                slices.add(createUnit(slice, unitType, sectionTitle, headingLevel, true));
            }

            if (end >= text.length()) {
                break;
            }

            start = Math.max(end - overlap, start + 1);
        }

        return slices;
    }

    /**
     * 在允许范围内寻找更自然的截断边界，优先命中句末或空白位置。
     */
    private int findBoundary(String text, int minIndex, int maxIndex) {
        for (int i = maxIndex; i > minIndex; i--) {
            char current = text.charAt(i - 1);
            if (isSentenceBoundary(current) || Character.isWhitespace(current)) {
                return i;
            }
        }
        return maxIndex;
    }

    /**
     * 按章节与长度规则将结构化单元组装为候选文本块。
     */
    private List<ChunkCandidate> assembleChunks(List<StructuredUnit> units, ChunkSettings chunkSettings) {
        List<ChunkCandidate> chunks = new ArrayList<>();
        List<StructuredUnit> currentUnits = new ArrayList<>();

        for (StructuredUnit unit : units) {
            if (unit.text().isBlank()) {
                continue;
            }

            if (currentUnits.isEmpty()) {
                currentUnits.add(unit);
                continue;
            }

            if (shouldStartNewChunk(currentUnits, unit, chunkSettings)) {
                chunks.add(toChunkCandidate(currentUnits));
                currentUnits = new ArrayList<>();
            }
            currentUnits.add(unit);
        }

        if (!currentUnits.isEmpty()) {
            chunks.add(toChunkCandidate(currentUnits));
        }

        return chunks;
    }

    /**
     * 对过短候选块做二次合并，优先保留同章节的语义连续性。
     */
    private List<ChunkCandidate> mergeShortChunks(List<ChunkCandidate> chunks, ChunkSettings chunkSettings) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<ChunkCandidate> merged = new ArrayList<>();
        for (ChunkCandidate chunk : chunks) {
            if (merged.isEmpty()) {
                merged.add(chunk);
                continue;
            }

            int lastIndex = merged.size() - 1;
            ChunkCandidate previous = merged.get(lastIndex);
            if (shouldMergeChunks(previous, chunk, chunkSettings)) {
                merged.set(lastIndex, mergeChunkCandidates(previous, chunk));
                continue;
            }

            merged.add(chunk);
        }

        if (merged.size() > 1) {
            int lastIndex = merged.size() - 1;
            ChunkCandidate last = merged.get(lastIndex);
            ChunkCandidate previous = merged.get(lastIndex - 1);
            if (last.text().length() < chunkSettings.minSize()
                    && canMergeChunkTexts(previous.text(), last.text(), chunkSettings)
                    && shareSection(previous, last)) {
                merged.set(lastIndex - 1, mergeChunkCandidates(previous, last));
                merged.remove(lastIndex);
            }
        }

        return merged;
    }

    /**
     * 将增强后的文本块与元数据封装为最终入库片段。
     */
    private TextSegment createSegment(
            String enhancedText,
            String rawText,
            String filename,
            String documentId,
            int index,
            String normalizedChunk,
            DocumentProfile profile,
            List<String> chunkKeywords,
            String chunkTitle) {
        Metadata metadata = new Metadata();
        metadata.put("filename", sanitizeUtf16(filename));
        metadata.put("documentId", sanitizeUtf16(documentId));
        metadata.put("chunkIndex", String.valueOf(index));
        metadata.put("chunkSize", String.valueOf(sanitizeUtf16(enhancedText).length()));
        metadata.put("rawChunkSize", String.valueOf(sanitizeUtf16(rawText).length()));
        metadata.put("chunkHash", Integer.toHexString(normalizedChunk.hashCode()));
        metadata.put("title", sanitizeUtf16(profile.title()));
        metadata.put("chunkTitle", sanitizeUtf16(chunkTitle));
        metadata.put("category", sanitizeUtf16(profile.category()));
        metadata.put("documentTime", sanitizeUtf16(profile.documentTime()));
        metadata.put("ingestedAt", sanitizeUtf16(profile.ingestedAt()));
        metadata.put("keywords", sanitizeUtf16(String.join(",", chunkKeywords)));
        metadata.put("documentKeywords", sanitizeUtf16(String.join(",", profile.keywords())));
        return TextSegment.from(sanitizeUtf16(enhancedText), metadata);
    }

    /**
     * 生成用于嵌入和检索的增强文本，统一拼接标题与关键词上下文。
     */
    private String buildEnhancedText(String title, String chunk, List<String> chunkKeywords) {
        StringBuilder builder = new StringBuilder();
        builder.append("【标题：").append(title).append("】\n");
        builder.append(chunk.trim());
        if (!chunkKeywords.isEmpty()) {
            builder.append("\n关键词：").append(String.join(", ", chunkKeywords));
        }
        return sanitizeUtf16(builder.toString());
    }

    /**
     * 结合文档级画像与块内容提取片段关键词。
     */
    private List<String> resolveChunkKeywords(DocumentProfile profile, String chunk) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (!"未分类".equals(profile.category())) {
            resolved.add(profile.category());
        }

        String chunkLower = chunk.toLowerCase(Locale.ROOT);
        for (String keyword : profile.keywords()) {
            if (chunkLower.contains(keyword.toLowerCase(Locale.ROOT))) {
                resolved.add(keyword);
            }
            if (resolved.size() >= keywordCount) {
                return new ArrayList<>(resolved);
            }
        }

        for (String keyword : extractKeywords(profile.title() + "\n" + chunk, profile.title(), keywordCount)) {
            resolved.add(keyword);
            if (resolved.size() >= keywordCount) {
                break;
            }
        }

        return new ArrayList<>(resolved);
    }

    /**
     * 为候选块生成最终标题，优先使用继承章节标题，再回退摘要标题。
     */
    private String resolveChunkTitle(DocumentProfile profile, ChunkCandidate chunk, List<String> chunkKeywords) {
        String sectionTitle = sanitizeUtf16(chunk.sectionTitle());
        if (!sectionTitle.isBlank() && !sectionTitle.equals(profile.title())) {
            return sanitizeUtf16(profile.title() + " / " + sectionTitle);
        }

        String chunkSummary = extractChunkSummary(chunk.text());
        if (chunkSummary.isBlank()) {
            return sanitizeUtf16(profile.title());
        }

        if (chunkSummary.equals(profile.title())) {
            return sanitizeUtf16(profile.title());
        }

        if (chunkKeywords.isEmpty()) {
            return sanitizeUtf16(profile.title() + " / " + chunkSummary);
        }

        for (String keyword : chunkKeywords) {
            if (chunkSummary.contains(keyword)) {
                return sanitizeUtf16(profile.title() + " / " + chunkSummary);
            }
        }

        return sanitizeUtf16(profile.title() + " / " + chunkSummary + "（" + chunkKeywords.getFirst() + "）");
    }

    /**
     * 从块内容中提炼简短摘要，作为无章节标题时的备用标题。
     */
    private String extractChunkSummary(String chunk) {
        List<String> paragraphs = Arrays.stream(sanitizeUtf16(chunk).split("\\n\\n+"))
            .map(String::trim)
            .filter(paragraph -> !paragraph.isBlank())
            .collect(Collectors.toList());
        if (paragraphs.isEmpty()) {
            return "";
        }

        int maxTitleLength = Math.max(8, chunkTitleMaxLength);
        int preferredBoundaryStart = Math.max(6, maxTitleLength - 6);
        int sentenceScanLimit = Math.max(maxTitleLength, maxTitleLength + 6);

        String firstParagraph = paragraphs.getFirst();
        if (isLikelyTitle(firstParagraph)) {
            return normalizeTitle(firstParagraph);
        }

        String firstSentence = extractLeadingSentence(firstParagraph, sentenceScanLimit);
        if (firstSentence.isBlank()) {
            return "";
        }

        if (firstSentence.length() <= maxTitleLength) {
            return sanitizeUtf16(firstSentence);
        }

        int boundary = firstSentence.length();
        for (int i = preferredBoundaryStart; i < Math.min(firstSentence.length(), sentenceScanLimit); i++) {
            char current = firstSentence.charAt(i);
            if (Character.isWhitespace(current) || current == '，' || current == '、' || current == '：' || current == ':') {
                boundary = i;
                break;
            }
        }

        String summary = firstSentence.substring(0, Math.min(boundary, maxTitleLength)).trim();
        if (summary.length() < 8) {
            summary = firstSentence.substring(0, Math.min(firstSentence.length(), maxTitleLength)).trim();
        }
        return sanitizeUtf16(summary);
    }

    /**
     * 提取段落开头句子，用于生成片段摘要标题。
     */
    private String extractLeadingSentence(String paragraph, int maxLength) {
        String normalized = sanitizeUtf16(paragraph).replace('\n', ' ').trim();
        if (normalized.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (isSentenceBoundary(current)) {
                break;
            }
            builder.append(current);
            if (builder.length() >= maxLength) {
                break;
            }
        }
        return builder.toString().trim();
    }

    /**
     * 根据文本内容创建结构化单元，并同步生成规范化判重文本。
     */
    private StructuredUnit createUnit(
            String text,
            UnitType unitType,
            String sectionTitle,
            int headingLevel,
            boolean sliced) {
        String safeText = sanitizeUtf16(text).trim();
        return new StructuredUnit(
            safeText,
            normalizeForDedup(safeText),
            unitType,
            sanitizeUtf16(sectionTitle),
            headingLevel,
            sliced
        );
    }

    /**
     * 识别段落是否为章节标题，并返回标题文本与层级信息。
     */
    private HeadingMatch matchHeading(String paragraph) {
        String normalized = sanitizeUtf16(paragraph).trim();
        if (normalized.isBlank()) {
            return HeadingMatch.notMatched();
        }

        Matcher markdownMatcher = MARKDOWN_HEADING_PATTERN.matcher(normalized);
        if (markdownMatcher.matches()) {
            String title = normalizeTitle(markdownMatcher.group(2));
            return title.isBlank() ? HeadingMatch.notMatched() : new HeadingMatch(true, title, markdownMatcher.group(1).length());
        }

        Matcher chineseMatcher = CHINESE_SECTION_HEADING_PATTERN.matcher(normalized);
        if (chineseMatcher.matches()) {
            String title = normalizeTitle(chineseMatcher.group(2));
            return title.isBlank() ? HeadingMatch.notMatched() : new HeadingMatch(true, title, 2);
        }

        Matcher numericMatcher = NUMERIC_SECTION_HEADING_PATTERN.matcher(normalized);
        if (numericMatcher.matches()) {
            String title = normalizeTitle(numericMatcher.group(2));
            int level = numericMatcher.group(1).chars().filter(ch -> ch == '.').map(ch -> 1).sum() + 1;
            return title.isBlank() ? HeadingMatch.notMatched() : new HeadingMatch(true, title, level);
        }

        Matcher numericParenMatcher = NUMERIC_SECTION_HEADING_PAREN_PATTERN.matcher(normalized);
        if (numericParenMatcher.matches()) {
            String title = normalizeTitle(numericParenMatcher.group(1));
            return title.isBlank() ? HeadingMatch.notMatched() : new HeadingMatch(true, title, 2);
        }

        if (isLikelyTitle(normalized) && looksLikeSectionHeading(normalized)) {
            return new HeadingMatch(true, normalizeTitle(normalized), 2);
        }
        return HeadingMatch.notMatched();
    }

    /**
     * 为短标题场景提供补充判断，降低章节标题漏识别概率。
     */
    private boolean looksLikeSectionHeading(String text) {
        return text.startsWith("背景")
            || text.startsWith("目标")
            || text.startsWith("方案")
            || text.startsWith("步骤")
            || text.startsWith("总结")
            || text.startsWith("说明")
            || text.startsWith("问题");
    }

    /**
     * 判断段落是否应按列表块处理。
     */
    private boolean isListParagraph(String paragraph) {
        String normalized = sanitizeUtf16(paragraph).trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (LIST_ITEM_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        return normalized.contains("\n") && Arrays.stream(normalized.split("\n"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .allMatch(line -> LIST_ITEM_PATTERN.matcher(line).matches());
    }

    /**
     * 将列表段拆为列表项，供超长列表优先按项切分。
     */
    private List<String> splitListItems(String paragraph) {
        String normalized = sanitizeUtf16(paragraph);
        List<String> lines = Arrays.stream(normalized.split("\n"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .collect(Collectors.toList());
        if (lines.size() > 1) {
            return lines;
        }

        Matcher matcher = Pattern.compile("(?=(?:[-*•]|\\d+[.)]|[一二三四五六七八九十]+、|（\\d+）)\\s+").matcher(normalized);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        if (starts.size() <= 1) {
            return List.of(normalized.trim());
        }

        List<String> items = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : normalized.length();
            String item = normalized.substring(start, end).trim();
            if (!item.isBlank()) {
                items.add(item);
            }
        }
        return items.isEmpty() ? List.of(normalized.trim()) : items;
    }

    /**
     * 判断下一个结构化单元是否应开启新块，兼顾章节边界与块大小。
     */
    private boolean shouldStartNewChunk(List<StructuredUnit> currentUnits, StructuredUnit nextUnit, ChunkSettings chunkSettings) {
        ChunkCandidate currentChunk = toChunkCandidate(currentUnits);
        if (currentChunk.text().length() + 2 + nextUnit.text().length() > chunkSettings.maxSize()) {
            return true;
        }

        StructuredUnit lastUnit = currentUnits.getLast();
        if (nextUnit.unitType() == UnitType.HEADING) {
            return !currentChunk.text().isBlank();
        }
        if (lastUnit.unitType() == UnitType.HEADING) {
            return false;
        }
        if (isDifferentSection(currentChunk.sectionTitle(), nextUnit.sectionTitle())) {
            return currentChunk.text().length() >= chunkSettings.minSize() / 2 || nextUnit.unitType() == UnitType.HEADING;
        }
        if (nextUnit.unitType() == UnitType.LIST && currentChunk.text().length() >= chunkSettings.targetSize()) {
            return true;
        }
        return false;
    }

    /**
     * 判断两个候选块是否适合合并，避免跨章节错误拼接。
     */
    private boolean shouldMergeChunks(ChunkCandidate previous, ChunkCandidate current, ChunkSettings chunkSettings) {
        if (!canMergeChunkTexts(previous.text(), current.text(), chunkSettings)) {
            return false;
        }
        if (isDifferentSection(previous.sectionTitle(), current.sectionTitle())) {
            return false;
        }
        if (shareSection(previous, current)) {
            return true;
        }
        return previous.text().length() < chunkSettings.minSize() / 2 && current.text().length() < chunkSettings.minSize() / 2;
    }

    /**
     * 判断两个文本块在长度约束下是否允许直接拼接。
     */
    private boolean canMergeChunkTexts(String previous, String current, ChunkSettings chunkSettings) {
        return previous.length() + 2 + current.length() <= chunkSettings.maxSize() + chunkSettings.minSize() / 2;
    }

    /**
     * 判断两个候选块是否属于同一章节。
     */
    private boolean shareSection(ChunkCandidate left, ChunkCandidate right) {
        return !left.sectionTitle().isBlank() && Objects.equals(left.sectionTitle(), right.sectionTitle());
    }

    /**
     * 判断两个章节标识是否明确指向不同章节。
     */
    private boolean isDifferentSection(String left, String right) {
        return !sanitizeUtf16(left).isBlank()
            && !sanitizeUtf16(right).isBlank()
            && !Objects.equals(sanitizeUtf16(left), sanitizeUtf16(right));
    }

    /**
     * 合并两个候选块，并保留原始结构化单元列表。
     */
    private ChunkCandidate mergeChunkCandidates(ChunkCandidate left, ChunkCandidate right) {
        List<StructuredUnit> mergedUnits = new ArrayList<>(left.units());
        mergedUnits.addAll(right.units());
        return toChunkCandidate(mergedUnits);
    }

    /**
     * 将结构化单元列表投影为候选块，汇总正文与章节信息。
     */
    private ChunkCandidate toChunkCandidate(List<StructuredUnit> units) {
        String sectionTitle = units.stream()
            .map(StructuredUnit::sectionTitle)
            .filter(title -> !title.isBlank())
            .findFirst()
            .orElse("");
        String text = units.stream()
            .map(StructuredUnit::text)
            .filter(content -> !content.isBlank())
            .collect(Collectors.joining("\n\n"))
            .trim();
        return new ChunkCandidate(List.copyOf(units), text, sectionTitle);
    }

    /**
     * 从清洗后的文本中提取文档级标题、分类、时间与关键词画像。
     */
    private DocumentProfile buildDocumentProfile(String filename, String cleanedText) {
        String safeCleanedText = sanitizeUtf16(cleanedText);
        String fallbackTitle = extractTitleFromFilename(filename);
        List<String> paragraphs = Arrays.stream(safeCleanedText.split("\\n\\n+"))
            .map(String::trim)
            .filter(paragraph -> !paragraph.isBlank())
            .collect(Collectors.toList());

        String firstParagraph = paragraphs.isEmpty() ? "" : paragraphs.getFirst();
        boolean titleFromContent = isLikelyTitle(firstParagraph);
        String title = titleFromContent ? normalizeTitle(firstParagraph) : fallbackTitle;

        String bodyText = titleFromContent
            ? paragraphs.stream().skip(1).collect(Collectors.joining("\n\n"))
            : safeCleanedText;
        if (bodyText.isBlank()) {
            bodyText = safeCleanedText;
        }

        String documentTime = extractDocumentTime(filename, safeCleanedText);
        String category = inferCategory(title, bodyText);
        List<String> keywords = extractKeywords(title + "\n" + bodyText, title, keywordCount);

        return new DocumentProfile(
            sanitizeUtf16(bodyText),
            sanitizeUtf16(title),
            sanitizeUtf16(category),
            sanitizeUtf16(documentTime),
            Instant.now().toString(),
            keywords.stream().map(this::sanitizeUtf16).collect(Collectors.toList())
        );
    }

    /**
     * 清洗原始文本，统一空白、换行与噪声字符。
     */
    private String cleanText(String text) {
        String normalized = sanitizeUtf16(text)
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\u00A0", " ")
            .replace("�", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "")
            .trim();

        List<String> cleanedLines = new ArrayList<>();
        for (String rawLine : normalized.split("\n", -1)) {
            String line = rawLine.replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) {
                cleanedLines.add("");
                continue;
            }
            if (isNoiseLine(line)) {
                continue;
            }
            cleanedLines.add(line);
        }

        return String.join("\n", cleanedLines)
            .replaceAll("\n{3,}", "\n\n")
            .replaceAll("(?<!\n)\n(?!\n)", " ")
            .replaceAll("[ ]{2,}", " ")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
    }

    /**
     * 判断一行是否属于页码或纯符号等噪声内容。
     */
    private boolean isNoiseLine(String line) {
        if (line.isBlank()) {
            return true;
        }

        String lowerLine = line.toLowerCase(Locale.ROOT);
        if (lowerLine.matches("^(第\\s*\\d+\\s*页|page\\s*\\d+|\\d+\\s*/\\s*\\d+)$")) {
            return true;
        }

        int meaningful = 0;
        int punctuation = 0;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (Character.isLetterOrDigit(current) || isHan(current)) {
                meaningful++;
            } else if (!Character.isWhitespace(current)) {
                punctuation++;
            }
        }

        return meaningful == 0 && punctuation > 0;
    }

    /**
     * 基于长度与标点特征判断段落是否可能是标题。
     */
    private boolean isLikelyTitle(String paragraph) {
        if (paragraph == null || paragraph.isBlank()) {
            return false;
        }

        String trimmed = paragraph.trim();
        if (trimmed.length() < 4 || trimmed.length() > 40) {
            return false;
        }
        if (FULL_DATE_PATTERN.matcher(trimmed).find() || YEAR_MONTH_PATTERN.matcher(trimmed).find()) {
            return false;
        }

        int sentenceBoundaryCount = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (isSentenceBoundary(trimmed.charAt(i))) {
                sentenceBoundaryCount++;
            }
        }
        return sentenceBoundaryCount <= 1;
    }

    /**
     * 规范化标题文本，移除常见前缀与无意义标记。
     */
    private String normalizeTitle(String title) {
        return sanitizeUtf16(title)
            .replaceFirst("^(标题[:：]\\s*)", "")
            .replaceAll("^[#*\\-\\s]+", "")
            .trim();
    }

    /**
     * 当正文无法稳定识别标题时，从文件名回退生成标题。
     */
    private String extractTitleFromFilename(String filename) {
        String safeFilename = sanitizeUtf16(filename);
        String baseName = safeFilename;
        int dotIndex = safeFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = safeFilename.substring(0, dotIndex);
        }
        return baseName
            .replace('_', ' ')
            .replace('-', ' ')
            .trim();
    }

    /**
     * 优先从正文，其次从文件名中提取文档时间信息。
     */
    private String extractDocumentTime(String filename, String text) {
        String extractedDate = findDate(text);
        if (extractedDate != null) {
            return extractedDate;
        }

        extractedDate = findDate(filename);
        if (extractedDate != null) {
            return extractedDate;
        }

        return LocalDate.now().toString();
    }

    /**
     * 从给定文本中识别标准化日期字符串。
     */
    private String findDate(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }

        Matcher fullDateMatcher = FULL_DATE_PATTERN.matcher(source);
        if (fullDateMatcher.find()) {
            return String.format("%s-%02d-%02d",
                fullDateMatcher.group(1),
                Integer.parseInt(fullDateMatcher.group(2)),
                Integer.parseInt(fullDateMatcher.group(3))
            );
        }

        Matcher yearMonthMatcher = YEAR_MONTH_PATTERN.matcher(source);
        if (yearMonthMatcher.find()) {
            return String.format("%s-%02d",
                yearMonthMatcher.group(1),
                Integer.parseInt(yearMonthMatcher.group(2))
            );
        }

        return null;
    }

    /**
     * 根据标题与正文关键词粗略推断文档分类。
     */
    private String inferCategory(String title, String bodyText) {
        String corpus = (title + "\n" + bodyText).toLowerCase(Locale.ROOT);
        Map<String, List<String>> categoryKeywords = createCategoryKeywords();

        String bestCategory = "未分类";
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : categoryKeywords.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (corpus.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score += keyword.length() >= 3 ? 2 : 1;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestCategory = entry.getKey();
            }
        }

        return bestScore >= 2 ? bestCategory : "未分类";
    }

    /**
     * 构建分类推断所使用的关键词映射表。
     */
    private Map<String, List<String>> createCategoryKeywords() {
        Map<String, List<String>> categoryKeywords = new LinkedHashMap<>();
        categoryKeywords.put("技术", List.of(
            "人工智能", "AI", "模型", "算法", "编程", "代码", "向量", "检索", "数据库",
            "RAG", "Java", "Python", "接口", "系统", "服务"
        ));
        categoryKeywords.put("产品", List.of(
            "产品", "运营", "用户", "增长", "转化", "市场", "需求", "功能", "商业"
        ));
        categoryKeywords.put("财经", List.of(
            "金融", "投资", "股票", "基金", "预算", "营收", "利润", "经济", "财务"
        ));
        categoryKeywords.put("教育", List.of(
            "学习", "教学", "教育", "课程", "学生", "培训", "考试", "知识点"
        ));
        categoryKeywords.put("医疗", List.of(
            "医疗", "健康", "疾病", "临床", "药物", "治疗", "患者", "诊断"
        ));
        categoryKeywords.put("法律", List.of(
            "法律", "合同", "合规", "诉讼", "条例", "法规", "责任", "条款"
        ));
        categoryKeywords.put("生活", List.of(
            "生活", "旅行", "饮食", "健身", "情感", "家庭", "习惯", "健康管理"
        ));
        return categoryKeywords;
    }

    /**
     * 综合拉丁词与中文词片提取关键词列表。
     */
    private List<String> extractKeywords(String text, String title, int limit) {
        Map<String, Integer> scores = new HashMap<>();
        addLatinKeywordScores(text, scores, 1);
        addLatinKeywordScores(title, scores, 5);
        addHanKeywordScores(text, title, scores, 1);
        addHanKeywordScores(title, title, scores, 6);

        return scores.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(entry -> entry.getKey().length(), java.util.Comparator.reverseOrder()))
            .map(Map.Entry::getKey)
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 为拉丁字符关键词累计权重分数。
     */
    private void addLatinKeywordScores(String source, Map<String, Integer> scores, int weight) {
        if (source == null || source.isBlank()) {
            return;
        }

        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(source.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (KEYWORD_STOP_WORDS.contains(token)) {
                continue;
            }
            scores.merge(token, weight, Integer::sum);
        }
    }

    /**
     * 为中文词片关键词累计权重分数。
     */
    private void addHanKeywordScores(String source, String title, Map<String, Integer> scores, int weight) {
        if (source == null || source.isBlank()) {
            return;
        }

        String titleText = title == null ? "" : title;
        Matcher matcher = HAN_SEQUENCE_PATTERN.matcher(source);
        while (matcher.find()) {
            String sequence = matcher.group();
            if (sequence.length() <= 4) {
                addHanKeyword(sequence, titleText, scores, weight);
                continue;
            }

            int maxLength = Math.min(4, sequence.length());
            for (int tokenLength = 2; tokenLength <= maxLength; tokenLength++) {
                for (int i = 0; i <= sequence.length() - tokenLength; i++) {
                    addHanKeyword(sequence.substring(i, i + tokenLength), titleText, scores, weight);
                }
            }
        }
    }

    /**
     * 校验中文关键词候选后，将其分数写入统计表。
     */
    private void addHanKeyword(String token, String title, Map<String, Integer> scores, int weight) {
        if (token.length() < 2 || token.length() > 4) {
            return;
        }
        if (KEYWORD_STOP_WORDS.contains(token) || isRepeatedCharacters(token)) {
            return;
        }

        int adjustedWeight = title.contains(token) ? weight + 3 : weight;
        scores.merge(token, adjustedWeight, Integer::sum);
    }

    /**
     * 判断词片是否由重复字符组成，用于过滤无效关键词。
     */
    private boolean isRepeatedCharacters(String token) {
        char first = token.charAt(0);
        for (int i = 1; i < token.length(); i++) {
            if (token.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * 规范化文本内容，生成用于去重比较的键。
     */
    private String normalizeForDedup(String text) {
        return sanitizeUtf16(text)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}【】《》“”‘’（）()、，。！？；：·…]", "");
    }

    /**
     * 清除不成对的 UTF-16 代理项，避免后续存储与序列化异常。
     */
    private String sanitizeUtf16(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }

        StringBuilder sanitized = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    sanitized.append(current).append(text.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(current)) {
                continue;
            }
            sanitized.append(current);
        }
        return sanitized.toString();
    }

    /**
     * 归一化分块相关配置，生成可直接使用的阈值集合。
     */
    private ChunkSettings resolveChunkSettings() {
        int maxSize = Math.max(120, chunkMaxSize);
        int minSize = Math.max(60, Math.min(chunkMinSize, maxSize));
        int targetSize = Math.max(minSize, Math.min(chunkSize, maxSize));
        return new ChunkSettings(minSize, targetSize, maxSize);
    }

    /**
     * 判断字符是否可作为句子级边界。
     */
    private boolean isSentenceBoundary(char value) {
        return value == '。'
            || value == '！'
            || value == '？'
            || value == '.'
            || value == '!'
            || value == '?'
            || value == '；'
            || value == ';';
    }

    /**
     * 判断字符是否属于中文汉字脚本。
     */
    private boolean isHan(char value) {
        Character.UnicodeScript script = Character.UnicodeScript.of(value);
        return script == Character.UnicodeScript.HAN;
    }

    /**
     * 使用PDFBox 3.x解析PDF文档
     *
     * @param inputStream PDF输入流
     * @return 提取的文本内容
     * @throws IOException 如果PDF解析失败
     */
    private String parsePdf(InputStream inputStream) throws IOException {
        log.debug("  正在读取PDF文件...");

        Path tempFile = Files.createTempFile("knowledge-upload-", ".pdf");
        try {
            Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long fileSize = Files.size(tempFile);
            log.debug("  PDF大小: {} 字节", fileSize);

            log.debug("  正在加载PDF文档...");
            try (PDDocument document = Loader.loadPDF(tempFile.toFile())) {
                int pageCount = document.getNumberOfPages();
                log.debug("  PDF页数: {}", pageCount);

                log.debug("  正在从PDF提取文本...");
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                String text = stripper.getText(document);

                String cleaned = text
                    .replaceAll("\\r\\n", "\n")
                    .replaceAll("\\s+$", "")
                    .trim();

                log.debug("  文本提取完成: {} 字符", cleaned.length());
                return cleaned;
            }
        } catch (Exception e) {
            log.error("PDF文档解析失败", e);
            throw new IOException("PDF解析失败: " + e.getMessage(), e);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 解析文本文档
     *
     * @param inputStream 文本输入流
     * @return 文本内容
     */
    private String parseText(InputStream inputStream) {
        log.debug("  正在读取文本文件...");

        try {
            byte[] bytes = inputStream.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length > 10 * 1024 * 1024) {
                throw new IllegalArgumentException("文本文件过大，当前仅支持不超过 10MB 的 TXT 文件");
            }
            String content = decodeTextBytes(bytes);

            log.debug("  文本文件读取成功: {} 字符", content.length());
            return content.trim();
        } catch (Exception e) {
            log.error("文本文档解析失败", e);
            throw new RuntimeException("文本解析失败: " + e.getMessage(), e);
        }
    }

    private String decodeTextBytes(byte[] bytes) throws CharacterCodingException {
        if (bytes.length == 0) {
            return "";
        }

        if (hasUtf8Bom(bytes)) {
            log.debug("  检测到 UTF-8 BOM，按 UTF-8 解码文本");
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (hasUtf16LeBom(bytes)) {
            log.debug("  检测到 UTF-16LE BOM，按 UTF-16LE 解码文本");
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (hasUtf16BeBom(bytes)) {
            log.debug("  检测到 UTF-16BE BOM，按 UTF-16BE 解码文本");
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }

        Charset utf16Charset = detectUtf16Charset(bytes);
        if (utf16Charset != null) {
            try {
                String decoded = decodeStrict(bytes, utf16Charset);
                log.debug("  检测到 UTF-16 文本特征，按 {} 解码文本", utf16Charset.displayName());
                return decoded;
            } catch (CharacterCodingException ignored) {
                log.debug("  UTF-16 特征检测命中，但严格解码失败，继续尝试其它编码");
            }
        }

        try {
            String decoded = decodeStrict(bytes, StandardCharsets.UTF_8);
            log.debug("  按 UTF-8 解码文本");
            return decoded;
        } catch (CharacterCodingException ignored) {
            String decoded = decodeStrict(bytes, Charset.forName("GB18030"));
            log.debug("  UTF-8 解码失败，回退按 GB18030 解码文本");
            return decoded;
        }
    }

    /**
     * 通过字节分布特征粗略判断文本是否为 UTF-16 编码。
     */
    private Charset detectUtf16Charset(byte[] bytes) {
        if (bytes.length < 4 || bytes.length % 2 != 0) {
            return null;
        }

        int evenZeroCount = 0;
        int oddZeroCount = 0;
        int pairCount = bytes.length / 2;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                if (i % 2 == 0) {
                    evenZeroCount++;
                } else {
                    oddZeroCount++;
                }
            }
        }

        double evenZeroRatio = evenZeroCount / (double) pairCount;
        double oddZeroRatio = oddZeroCount / (double) pairCount;
        if (oddZeroRatio > 0.3 && oddZeroRatio > evenZeroRatio * 2) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenZeroRatio > 0.3 && evenZeroRatio > oddZeroRatio * 2) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    /**
     * 以严格模式按指定字符集解码文本，发现异常时直接抛错。
     */
    private String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer buffer = decoder.decode(ByteBuffer.wrap(bytes));
        return buffer.toString();
    }

    /**
     * 判断字节流是否带有 UTF-8 BOM。
     */
    private boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF;
    }

    /**
     * 判断字节流是否带有 UTF-16 LE BOM。
     */
    private boolean hasUtf16LeBom(byte[] bytes) {
        return bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xFE;
    }

    /**
     * 判断字节流是否带有 UTF-16 BE BOM。
     */
    private boolean hasUtf16BeBom(byte[] bytes) {
        return bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFE
            && (bytes[1] & 0xFF) == 0xFF;
    }

    /**
     * 处理后的文档结果
     *
     * @param documentId 唯一文档标识符
     * @param filename 原始文件名
     * @param segments 带元数据的文本块列表
     */
    public record ProcessedDocument(
        String documentId,
        String filename,
        List<TextSegment> segments
    ) {
    }

    private record ChunkSettings(
        int minSize,
        int targetSize,
        int maxSize
    ) {
    }

    private record ChunkBuildResult(
        List<TextSegment> segments,
        int filteredShortCount,
        int filteredDuplicateCount
    ) {
    }

    private record DeduplicationResult(
        List<StructuredUnit> units,
        int filteredDuplicateCount
    ) {
    }

    private record StructuredUnit(
        String text,
        String normalizedText,
        UnitType unitType,
        String sectionTitle,
        int headingLevel,
        boolean sliced
    ) {
    }

    private record ChunkCandidate(
        List<StructuredUnit> units,
        String text,
        String sectionTitle
    ) {
    }

    private record HeadingMatch(
        boolean matched,
        String title,
        int level
    ) {
        private static HeadingMatch notMatched() {
            return new HeadingMatch(false, "", 0);
        }
    }

    private enum UnitType {
        HEADING,
        PARAGRAPH,
        LIST
    }

    private record DocumentProfile(
        String bodyText,
        String title,
        String category,
        String documentTime,
        String ingestedAt,
        List<String> keywords
    ) {
    }
}