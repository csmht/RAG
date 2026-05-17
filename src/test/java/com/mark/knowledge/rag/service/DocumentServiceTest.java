package com.mark.knowledge.rag.service;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentServiceTest {

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService();
        ReflectionTestUtils.setField(documentService, "chunkSize", 320);
        ReflectionTestUtils.setField(documentService, "chunkMinSize", 250);
        ReflectionTestUtils.setField(documentService, "chunkMaxSize", 350);
        ReflectionTestUtils.setField(documentService, "chunkOverlap", 40);
        ReflectionTestUtils.setField(documentService, "minTextLength", 80);
        ReflectionTestUtils.setField(documentService, "keywordCount", 6);
        ReflectionTestUtils.setField(documentService, "keywordMinTokenLength", 2);
        ReflectionTestUtils.setField(documentService, "keywordMinOccurrences", 2);
        ReflectionTestUtils.setField(documentService, "keywordChunkRatioThreshold", 0.6d);
        ReflectionTestUtils.setField(documentService, "keywordMaxCandidateMultiplier", 4);
        ReflectionTestUtils.setField(documentService, "keywordUseSmartIk", true);
    }

    @Test
    void shouldCleanEnhanceAndAttachMetadataBeforeEmbedding() {
        String text = """
            AI 知识库建设指南

            2025-03-15

            第 1 页
            \uFEFFRAG 系统在企业知识管理中非常重要，它可以把文档检索、向量召回和答案生成串起来，让使用者更快找到可靠结论。为了保证检索效果，我们通常会先做文本清洗、结构化切块和关键词增强，再把处理后的内容送入向量数据库中。高质量的数据准备会直接影响召回质量、回答准确率以及后续的重排效果。

            在实际项目中，知识库文档往往来自 PDF、会议纪要和说明文档，它们常常包含分页符、乱码字符和格式噪声。为了让嵌入模型更稳定，我们会去掉无效符号、统一空白字符、补充标题上下文，并且给每个片段增加分类、时间和关键词元数据，这样后续无论是向量检索还是 BM25 重排都会更容易命中真正相关的内容。�
            """;

        DocumentService.ProcessedDocument processedDocument = documentService.processDocument(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
            "rag-guide.txt"
        );

        List<TextSegment> segments = processedDocument.segments();
        assertFalse(segments.isEmpty());

        TextSegment firstSegment = segments.getFirst();
        assertTrue(firstSegment.text().startsWith("【段落关键词：") || firstSegment.text().startsWith("【标题：AI 知识库建设指南】"));
        assertTrue(firstSegment.text().contains("【标题：AI 知识库建设指南】"));
        assertFalse(firstSegment.text().contains("第 1 页"));
        assertFalse(firstSegment.text().contains("�"));

        assertEquals("AI 知识库建设指南", firstSegment.metadata().getString("title"));
        assertEquals("技术", firstSegment.metadata().getString("category"));
        assertEquals("2025-03-15", firstSegment.metadata().getString("documentTime"));
        assertTrue(Integer.parseInt(firstSegment.metadata().getString("rawChunkSize")) >= 80);
    }

    @Test
    void shouldFilterShortAndDuplicateChunks() {
        String duplicateParagraph = """
            向量检索系统需要稳定的文档预处理流程，这包括文本清洗、合理切块、标题增强和关键词补充。只有在内容结构清楚、噪声被去掉之后，嵌入模型才能生成更可靠的向量，知识库检索也才能在复杂问题下保持稳定。为了减少无效召回，系统还需要为每个片段记录来源、分类和时间等元数据。
            """;
        String text = """
            重复片段测试

            %s

            %s

            简讯
            """.formatted(duplicateParagraph, duplicateParagraph);

        DocumentService.ProcessedDocument processedDocument = documentService.processDocument(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
            "duplicate-check.txt"
        );

        List<TextSegment> segments = processedDocument.segments();
        assertEquals(1, segments.size());

        Set<String> hashes = segments.stream()
            .map(segment -> segment.metadata().getString("chunkHash"))
            .collect(Collectors.toSet());
        assertEquals(segments.size(), hashes.size());
    }

    @Test
    void shouldRemoveIllFormedUtf16BeforeStoringSegments() {
        String text = """
            坏字符测试

            2025-03-15

            %sRAG 在处理知识库文档时，需要先完成清洗、切块和关键词增强，再把稳定的文本送入向量数据库。只有把乱码和不合法字符提前去掉，后续的 protobuf 序列化和向量存储过程才不会出现告警，整个上传流程也会更稳定。
            """.formatted("\uD83D");

        DocumentService.ProcessedDocument processedDocument = documentService.processDocument(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
            "broken-utf16.txt"
        );

        TextSegment firstSegment = processedDocument.segments().getFirst();
        assertFalse(firstSegment.text().contains("\uD83D"));
        assertFalse(firstSegment.metadata().getString("title").contains("\uD83D"));
        assertFalse(firstSegment.metadata().getString("keywords").contains("\uD83D"));
    }

    @Test
    void shouldDecodeGb18030TextWithoutGarbledChinese() {
        String text = """
            中文编码测试

            2025-03-15

            向量数据库中的中文内容在入库前需要先用正确的字符集读取，否则存进去再取出来就会出现乱码。这里使用 GB18030 编码来模拟常见的本地中文文本文件，系统应该能正确解析并保留原始中文语义。
            """;

        DocumentService.ProcessedDocument processedDocument = documentService.processDocument(
            new ByteArrayInputStream(text.getBytes(Charset.forName("GB18030"))),
            "gb18030-sample.txt"
        );

        TextSegment firstSegment = processedDocument.segments().getFirst();
        assertTrue(firstSegment.text().contains("中文编码测试"));
        assertTrue(firstSegment.text().contains("向量数据库中的中文内容"));
        assertFalse(firstSegment.text().contains("锟"));
        assertFalse(firstSegment.text().contains("�"));
    }

    @Test
    void shouldPrioritizeChunkSpecificKeywordsBeforeDocumentLevelKeywords() throws Exception {
        Method buildFrequencyMethod = DocumentService.class.getDeclaredMethod("buildTermFrequency", String.class);
        buildFrequencyMethod.setAccessible(true);
        Method resolveKeywordsMethod = DocumentService.class.getDeclaredMethod(
            "resolveChunkKeywords",
            Class.forName("com.mark.knowledge.rag.service.DocumentService$DocumentProfile"),
            String.class,
            java.util.Map.class
        );
        resolveKeywordsMethod.setAccessible(true);
        Method buildEnhancedTextMethod = DocumentService.class.getDeclaredMethod(
            "buildEnhancedText",
            String.class,
            String.class,
            List.class
        );
        buildEnhancedTextMethod.setAccessible(true);

        String fullText = """
            RAG 的搭建通常包括数据清洗、分块、向量化和检索链路设计，这里重点介绍整体方案背景与基础流程。

            搭建 RAG 需要关注物理配置，包括 GPU 显存、CPU 核数、内存容量和磁盘空间。显存不足会直接影响向量模型部署，显存规划也会影响部署稳定性。配置规划同样会影响资源利用率。
            """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> frequency = (java.util.Map<String, Integer>) buildFrequencyMethod.invoke(
            documentService,
            fullText
        );

        Class<?> documentProfileClass = Class.forName("com.mark.knowledge.rag.service.DocumentService$DocumentProfile");
        var documentProfileConstructor = documentProfileClass.getDeclaredConstructors()[0];
        documentProfileConstructor.setAccessible(true);
        Object profile = documentProfileConstructor.newInstance(
            "body",
            "RAG 的搭建",
            "技术",
            "2025-03-15",
            "2025-03-15T00:00:00Z",
            List.of("RAG", "搭建", "检索", "物理配置", "显存")
        );

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) resolveKeywordsMethod.invoke(
            documentService,
            profile,
            "搭建 RAG 需要关注物理配置，包括 GPU 显存、CPU 核数、内存容量和磁盘空间。显存不足会直接影响向量模型部署，显存规划也会影响部署稳定性。配置规划同样会影响资源利用率。",
            frequency
        );

        assertTrue(keywords.contains("显存"));
        assertTrue(keywords.contains("配置") || keywords.contains("物理配置") || keywords.contains("内存"));
        if (keywords.contains("搭建")) {
            int configKeywordIndex = keywords.contains("物理配置")
                ? keywords.indexOf("物理配置")
                : (keywords.contains("配置") ? keywords.indexOf("配置") : keywords.indexOf("内存"));
            assertTrue(configKeywordIndex < keywords.indexOf("搭建"));
        }

        String enhancedText = (String) buildEnhancedTextMethod.invoke(documentService, "RAG 的搭建", "正文", keywords);
        assertTrue(enhancedText.startsWith("【段落关键词："));
    }

    @Test
    void shouldRespectKeywordCountWhenSpecificKeywordsExceedLimit() throws Exception {
        ReflectionTestUtils.setField(documentService, "keywordCount", 3);

        Method buildFrequencyMethod = DocumentService.class.getDeclaredMethod("buildTermFrequency", String.class);
        buildFrequencyMethod.setAccessible(true);
        Method resolveKeywordsMethod = DocumentService.class.getDeclaredMethod(
            "resolveChunkKeywords",
            Class.forName("com.mark.knowledge.rag.service.DocumentService$DocumentProfile"),
            String.class,
            java.util.Map.class
        );
        resolveKeywordsMethod.setAccessible(true);

        String chunk = "显存规划需要结合显存容量、GPU 型号、内存容量、磁盘吞吐和部署方式综合评估。显存容量、内存容量和部署方式会直接影响部署稳定性。";
        String fullText = chunk + "\n\nRAG 搭建介绍主要说明整体概念与流程。";

        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> frequency = (java.util.Map<String, Integer>) buildFrequencyMethod.invoke(documentService, fullText);

        Class<?> documentProfileClass = Class.forName("com.mark.knowledge.rag.service.DocumentService$DocumentProfile");
        var documentProfileConstructor = documentProfileClass.getDeclaredConstructors()[0];
        documentProfileConstructor.setAccessible(true);
        Object profile = documentProfileConstructor.newInstance(
            "body",
            "RAG 的搭建",
            "技术",
            "2025-03-15",
            "2025-03-15T00:00:00Z",
            List.of("显存容量", "内存容量", "部署方式", "GPU", "磁盘吞吐")
        );

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) resolveKeywordsMethod.invoke(documentService, profile, chunk, frequency);

        assertEquals(3, keywords.size());
    }

    @Test
    void shouldUseFullEnhancementPipelineWhenProcessingPlainTextContent() {
        String text = """
            RAG 的搭建

            2025-03-15

            RAG 的整体搭建通常会先说明检索增强生成的基本原理和整体链路。

            搭建 RAG 需要重点评估物理配置，尤其是 GPU 显存、CPU 核数、内存容量和磁盘空间。显存不足会直接影响部署效果，显存规划也会影响系统稳定性。
            """;

        List<TextSegment> segments = documentService.processDocumentContent(text, "rag-build.txt");

        assertFalse(segments.isEmpty());
        TextSegment firstSegment = segments.getFirst();
        assertTrue(firstSegment.text().contains("【标题：RAG 的搭建】"));
        assertTrue(firstSegment.metadata().getString("keywords") != null);
        assertEquals("RAG 的搭建", firstSegment.metadata().getString("title"));
        assertEquals("2025-03-15", firstSegment.metadata().getString("documentTime"));
        assertTrue(firstSegment.metadata().getString("chunkHash") != null);
    }
}
