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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档服务 - 文档处理和分块
 *
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

    private ChunkBuildResult splitText(
            DocumentProfile profile,
            String filename,
            String documentId,
            ChunkSettings chunkSettings) {
        log.debug("开始文本切分过程...");
        log.debug("  文本总长度: {} 字符", profile.bodyText().length());

        DeduplicationResult unitDeduplication = deduplicateUnits(splitIntoUnits(profile.bodyText(), chunkSettings));
        List<String> chunks = mergeShortChunks(assembleChunks(unitDeduplication.units(), chunkSettings), chunkSettings);

        List<TextSegment> segments = new ArrayList<>();
        Set<String> seenChunks = new HashSet<>();
        int filteredShortCount = 0;
        int filteredDuplicateCount = unitDeduplication.filteredDuplicateCount();
        int chunkIndex = 0;

        for (String chunk : chunks) {
            String normalizedChunk = normalizeForDedup(chunk);
            if (normalizedChunk.length() < minTextLength) {
                filteredShortCount++;
                continue;
            }

            if (!seenChunks.add(normalizedChunk)) {
                filteredDuplicateCount++;
                continue;
            }

            List<String> chunkKeywords = resolveChunkKeywords(profile, chunk);
            String enhancedText = buildEnhancedText(profile.title(), chunk, chunkKeywords);
            segments.add(createSegment(
                enhancedText,
                chunk,
                filename,
                documentId,
                chunkIndex++,
                normalizedChunk,
                profile,
                chunkKeywords
            ));
        }

        log.debug("文本切分完成: 创建了 {} 个文本块", segments.size());
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("文档清洗后没有足够的有效文本可入库");
        }

        return new ChunkBuildResult(segments, filteredShortCount, filteredDuplicateCount);
    }

    private DeduplicationResult deduplicateUnits(List<String> units) {
        List<String> uniqueUnits = new ArrayList<>();
        Set<String> seenUnits = new HashSet<>();
        int filteredDuplicateCount = 0;

        for (String unit : units) {
            String normalizedUnit = normalizeForDedup(unit);
            if (normalizedUnit.isBlank()) {
                continue;
            }
            if (!seenUnits.add(normalizedUnit)) {
                filteredDuplicateCount++;
                continue;
            }
            uniqueUnits.add(unit);
        }

        return new DeduplicationResult(uniqueUnits, filteredDuplicateCount);
    }

    private List<String> splitIntoUnits(String text, ChunkSettings chunkSettings) {
        List<String> paragraphs = Arrays.stream(text.split("\\n\\n+"))
            .map(String::trim)
            .filter(paragraph -> !paragraph.isBlank())
            .collect(Collectors.toList());
        log.debug("  发现 {} 个段落", paragraphs.size());

        List<String> units = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.length() <= chunkSettings.maxSize()) {
                units.add(paragraph);
                continue;
            }
            units.addAll(splitLongParagraph(paragraph, chunkSettings));
        }
        return units;
    }

    private List<String> splitLongParagraph(String paragraph, ChunkSettings chunkSettings) {
        List<String> units = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[。！？!?；;])");
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            String trimmedSentence = sentence.trim();
            if (trimmedSentence.isBlank()) {
                continue;
            }

            if (trimmedSentence.length() > chunkSettings.maxSize()) {
                if (!current.isEmpty()) {
                    units.add(current.toString().trim());
                    current.setLength(0);
                }
                units.addAll(sliceLongText(trimmedSentence, chunkSettings));
                continue;
            }

            if (current.isEmpty()) {
                current.append(trimmedSentence);
                continue;
            }

            if (current.length() + 1 + trimmedSentence.length() <= chunkSettings.maxSize()) {
                current.append(' ').append(trimmedSentence);
            } else {
                units.add(current.toString().trim());
                current.setLength(0);
                current.append(trimmedSentence);
            }
        }

        if (!current.isEmpty()) {
            units.add(current.toString().trim());
        }

        return units;
    }

    private List<String> sliceLongText(String text, ChunkSettings chunkSettings) {
        List<String> slices = new ArrayList<>();
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
                slices.add(slice);
            }

            if (end >= text.length()) {
                break;
            }

            start = Math.max(end - overlap, start + 1);
        }

        return slices;
    }

    private int findBoundary(String text, int minIndex, int maxIndex) {
        for (int i = maxIndex; i > minIndex; i--) {
            char current = text.charAt(i - 1);
            if (isSentenceBoundary(current) || Character.isWhitespace(current)) {
                return i;
            }
        }
        return maxIndex;
    }

    private List<String> assembleChunks(List<String> units, ChunkSettings chunkSettings) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String unit : units) {
            if (unit.isBlank()) {
                continue;
            }

            if (current.isEmpty()) {
                current.append(unit);
                continue;
            }

            if (current.length() + 2 + unit.length() <= chunkSettings.maxSize()) {
                current.append("\n\n").append(unit);
            } else {
                chunks.add(current.toString().trim());
                current.setLength(0);
                current.append(unit);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    private List<String> mergeShortChunks(List<String> chunks, ChunkSettings chunkSettings) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<String> merged = new ArrayList<>();
        for (String chunk : chunks) {
            if (merged.isEmpty()) {
                merged.add(chunk);
                continue;
            }

            int lastIndex = merged.size() - 1;
            String previous = merged.get(lastIndex);
            if ((previous.length() < chunkSettings.minSize() || chunk.length() < chunkSettings.minSize())
                    && previous.length() + 2 + chunk.length() <= chunkSettings.maxSize() + chunkSettings.minSize() / 2) {
                merged.set(lastIndex, previous + "\n\n" + chunk);
                continue;
            }

            merged.add(chunk);
        }

        if (merged.size() > 1) {
            int lastIndex = merged.size() - 1;
            String last = merged.get(lastIndex);
            String previous = merged.get(lastIndex - 1);
            if (last.length() < chunkSettings.minSize()
                    && previous.length() + 2 + last.length() <= chunkSettings.maxSize() + chunkSettings.minSize() / 2) {
                merged.set(lastIndex - 1, previous + "\n\n" + last);
                merged.remove(lastIndex);
            }
        }

        return merged;
    }

    private TextSegment createSegment(
            String enhancedText,
            String rawText,
            String filename,
            String documentId,
            int index,
            String normalizedChunk,
            DocumentProfile profile,
            List<String> chunkKeywords) {
        Metadata metadata = new Metadata();
        metadata.put("filename", sanitizeUtf16(filename));
        metadata.put("documentId", sanitizeUtf16(documentId));
        metadata.put("chunkIndex", String.valueOf(index));
        metadata.put("chunkSize", String.valueOf(sanitizeUtf16(enhancedText).length()));
        metadata.put("rawChunkSize", String.valueOf(sanitizeUtf16(rawText).length()));
        metadata.put("chunkHash", Integer.toHexString(normalizedChunk.hashCode()));
        metadata.put("title", sanitizeUtf16(profile.title()));
        metadata.put("category", sanitizeUtf16(profile.category()));
        metadata.put("documentTime", sanitizeUtf16(profile.documentTime()));
        metadata.put("ingestedAt", sanitizeUtf16(profile.ingestedAt()));
        metadata.put("keywords", sanitizeUtf16(String.join(",", chunkKeywords)));
        metadata.put("documentKeywords", sanitizeUtf16(String.join(",", profile.keywords())));
        return TextSegment.from(sanitizeUtf16(enhancedText), metadata);
    }

    private String buildEnhancedText(String title, String chunk, List<String> chunkKeywords) {
        StringBuilder builder = new StringBuilder();
        builder.append("【标题：").append(title).append("】\n");
        builder.append(chunk.trim());
        if (!chunkKeywords.isEmpty()) {
            builder.append("\n关键词：").append(String.join(", ", chunkKeywords));
        }
        return sanitizeUtf16(builder.toString());
    }

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

    private String normalizeTitle(String title) {
        return sanitizeUtf16(title)
            .replaceFirst("^(标题[:：]\\s*)", "")
            .replaceAll("^[#*\\-\\s]+", "")
            .trim();
    }

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

    private boolean isRepeatedCharacters(String token) {
        char first = token.charAt(0);
        for (int i = 1; i < token.length(); i++) {
            if (token.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private String normalizeForDedup(String text) {
        return sanitizeUtf16(text)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}【】《》“”‘’（）()、，。！？；：·…]", "");
    }

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

    private ChunkSettings resolveChunkSettings() {
        int maxSize = Math.max(120, chunkMaxSize);
        int minSize = Math.max(60, Math.min(chunkMinSize, maxSize));
        int targetSize = Math.max(minSize, Math.min(chunkSize, maxSize));
        return new ChunkSettings(minSize, targetSize, maxSize);
    }

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
        log.debug("  正在读取PDF字节流...");

        byte[] bytes = inputStream.readAllBytes();
        log.debug("  PDF大小: {} 字节", bytes.length);

        log.debug("  正在加载PDF文档...");
        try (PDDocument document = Loader.loadPDF(bytes)) {
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
        } catch (Exception e) {
            log.error("PDF文档解析失败", e);
            throw new IOException("PDF解析失败: " + e.getMessage(), e);
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
            byte[] bytes = inputStream.readAllBytes();
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

    private String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer buffer = decoder.decode(ByteBuffer.wrap(bytes));
        return buffer.toString();
    }

    private boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF;
    }

    private boolean hasUtf16LeBom(byte[] bytes) {
        return bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xFE;
    }

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
        List<String> units,
        int filteredDuplicateCount
    ) {
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
