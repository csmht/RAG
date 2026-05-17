package com.mark.knowledge.rag.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG 会话记忆治理服务。
 */
@Service
public class RagMemoryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RagMemoryOrchestrator.class);

    @Value("${rag.memory-fact-score-threshold:0.6}")
    private double memoryFactScoreThreshold = 0.6;

    @Value("${rag.memory-max-facts-per-update:3}")
    private int memoryMaxFactsPerUpdate = 3;

    @Value("${rag.memory-top-match-max-length:300}")
    private int memoryTopMatchMaxLength = 300;

    @Value("${rag.memory-intent-max-source-length:500}")
    private int memoryIntentMaxSourceLength = 500;

    @Value("${rag.memory-summary-source-max-length:4000}")
    private int memorySummarySourceMaxLength = 4000;

    @Value("${rag.memory-summary-enabled:true}")
    private boolean memorySummaryEnabled = true;

    @Value("${rag.memory-summary-model-enabled:true}")
    private boolean memorySummaryModelEnabled = true;

    private final ChatModel chatModel;
    private final ConversationMemoryService conversationMemoryService;
    private final RagContextAssembler ragContextAssembler;
    private final ConcurrentHashMap<String, AtomicBoolean> summaryCompressionStates = new ConcurrentHashMap<>();

    /**
     * 创建会话记忆治理服务。
     */
    public RagMemoryOrchestrator(
            ChatModel chatModel,
            ConversationMemoryService conversationMemoryService,
            RagContextAssembler ragContextAssembler) {
        this.chatModel = chatModel;
        this.conversationMemoryService = conversationMemoryService;
        this.ragContextAssembler = ragContextAssembler;
    }

    /**
     * 获取指定会话的记忆快照。
     */
    public ConversationMemoryService.ConversationMemorySnapshot getMemorySnapshot(String conversationId) {
        return conversationMemoryService.getMemorySnapshot(conversationId);
    }

    /**
     * 根据原始问题与改写问题更新会话意图。
     */
    public void updateIntentFromQuestion(String conversationId, ConversationMemoryService.ConversationMemorySnapshot memory, String question, String rewrittenQuestion) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(question) || !StringUtils.hasText(rewrittenQuestion)) {
            return;
        }
        String extractedIntent = extractIntentFromQuestion(memory, question, rewrittenQuestion);
        String normalizedIntent = normalizeExtractedIntent(extractedIntent);
        if (normalizedIntent != null) {
            conversationMemoryService.updateIntent(conversationId, normalizedIntent);
        }
    }

    /**
     * 根据最高相关检索片段更新会话事实。
     */
    public void updateFactsFromTopMatch(String conversationId, List<EmbeddingMatch<TextSegment>> matches) {
        if (!StringUtils.hasText(conversationId) || matches == null || matches.isEmpty()) {
            return;
        }
        EmbeddingMatch<TextSegment> topMatch = matches.stream()
            .filter(match -> match != null && match.embedded() != null && match.score() != null)
            .max(Comparator.comparingDouble(EmbeddingMatch::score))
            .orElse(null);
        if (topMatch == null || topMatch.score() < memoryFactScoreThreshold) {
            return;
        }
        List<String> normalizedFacts = normalizeExtractedFacts(extractFactsFromMatch(topMatch));
        if (!normalizedFacts.isEmpty()) {
            conversationMemoryService.updateFacts(conversationId, normalizedFacts);
        }
    }

    /**
     * 记录同步场景下的一轮会话消息并尝试触发摘要压缩。
     */
    public void appendRoundMessages(String conversationId, String question, String answer) {
        conversationMemoryService.appendUserMessage(conversationId, question);
        triggerAsyncSummaryCompression(conversationId);
        conversationMemoryService.appendAssistantMessage(conversationId, answer);
    }

    /**
     * 异步触发指定会话的历史摘要压缩任务。
     */
    public void triggerAsyncSummaryCompression(String conversationId) {
        if (!memorySummaryEnabled || !StringUtils.hasText(conversationId)) {
            return;
        }
        if (!conversationMemoryService.shouldCompressSummary(conversationId)) {
            return;
        }
        AtomicBoolean state = summaryCompressionStates.computeIfAbsent(conversationId, ignored -> new AtomicBoolean(false));
        if (!state.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> compressSummaryAsync(conversationId, state));
    }

    /**
     * 读取摘要压缩源文本并更新历史摘要。
     */
    private void compressSummaryAsync(String conversationId, AtomicBoolean state) {
        try {
            String source = conversationMemoryService.getSummarySource(conversationId);
            if (!StringUtils.hasText(source)) {
                return;
            }
            String summary = memorySummaryModelEnabled
                ? summarizeHistory(source)
                : RagTextSupport.trimToMaxLength(source, memorySummarySourceMaxLength);
            if (StringUtils.hasText(summary)) {
                conversationMemoryService.updateSummary(conversationId, summary);
            }
        } catch (Exception e) {
            log.warn("异步压缩历史摘要失败: conversationId={}, message={}", conversationId, e.getMessage());
        } finally {
            state.set(false);
            summaryCompressionStates.remove(conversationId, state);
        }
    }

    /**
     * 调用模型压缩历史摘要文本。
     */
    private String summarizeHistory(String source) {
        String prompt = buildSummaryCompressionPrompt(source);
        String response = chatModel.chat(prompt);
        return response == null ? null : response.trim();
    }

    /**
     * 提取当前轮次的核心意图。
     */
    private String extractIntentFromQuestion(ConversationMemoryService.ConversationMemorySnapshot memory, String question, String rewrittenQuestion) {
        String prompt = buildIntentExtractionPrompt(memory, question, rewrittenQuestion);
        String response = chatModel.chat(prompt);
        return response == null ? null : response.trim();
    }

    /**
     * 构建意图提取使用的 Prompt。
     */
    private String buildIntentExtractionPrompt(ConversationMemoryService.ConversationMemorySnapshot memory, String question, String rewrittenQuestion) {
        String memoryContext = ragContextAssembler.buildMemoryContext(memory);
        String safeMemoryContext = StringUtils.hasText(memoryContext)
            ? RagTextSupport.trimToMaxLength(memoryContext, memoryIntentMaxSourceLength)
            : "无";
        return String.format("""
            你需要根据会话记忆、用户原问题和改写后的问题，总结当前轮次最核心的会话意图。
            只输出一句简短中文短语，不要解释，不要分点，不要加前缀。

            会话记忆：
            %s

            原问题：
            %s

            改写问题：
            %s
            """, safeMemoryContext, question, rewrittenQuestion);
    }

    /**
     * 清洗意图提取结果，过滤无效占位文本。
     */
    private String normalizeExtractedIntent(String intent) {
        if (!StringUtils.hasText(intent)) {
            return null;
        }
        String normalized = intent.trim()
            .replace("当前意图：", "")
            .replace("意图：", "")
            .trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (RagTextSupport.INVALID_MARKERS.contains(normalized)) {
            return null;
        }
        return normalized;
    }

    /**
     * 从最高相关片段中直接提取原文事实列表。
     */
    private List<String> extractFactsFromMatch(EmbeddingMatch<TextSegment> topMatch) {
        TextSegment segment = topMatch.embedded();
        if (segment == null || !StringUtils.hasText(segment.text())) {
            return List.of();
        }
        String content = RagTextSupport.trimToMaxLength(segment.text(), memoryTopMatchMaxLength);
        return content.lines().map(String::trim).filter(StringUtils::hasText).toList();
    }

    /**
     * 清洗事实提取结果，过滤无效或占位内容。
     */
    private List<String> normalizeExtractedFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<String> invalidMarkers = RagTextSupport.INVALID_MARKERS;
        List<String> normalized = new ArrayList<>();
        for (String fact : facts) {
            if (!StringUtils.hasText(fact)) {
                continue;
            }
            String value = fact.trim()
                .replaceFirst("^[\\-•*\\d.、\\s]+", "")
                .trim();
            if (!StringUtils.hasText(value) || invalidMarkers.contains(value)) {
                continue;
            }
            normalized.add(value);
            if (normalized.size() >= Math.max(memoryMaxFactsPerUpdate, 1)) {
                break;
            }
        }
        return normalized;
    }

    /**
     * 构建历史摘要压缩使用的 Prompt。
     */
    private String buildSummaryCompressionPrompt(String source) {
        return String.format("""
            你需要将以下会话历史压缩为一段简洁、稳定、可复用的中文历史摘要。
            保留关键信息、主要结论、长期有效约束，去掉重复与冗余表述。
            只输出摘要正文，不要加标题，不要解释。

            会话历史：
            %s
            """, RagTextSupport.trimToMaxLength(source, memorySummarySourceMaxLength));
    }
}
