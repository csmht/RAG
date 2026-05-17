package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import com.mark.knowledge.rag.dto.RagRetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Map;

/**
 * RAG 问答服务。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String EMPTY_MATCH_ANSWER = "未在已上传文档中检索到足够相关的内容，请根据文档内容重新提问。";

    @Value("${rag.stream-timeout-ms:300000}")
    private long streamTimeoutMs = 300000L;

    @Value("${rag.memory-top-match-max-length:300}")
    private int memoryTopMatchMaxLength = 300;

    @Value("${rag.memory-intent-max-source-length:500}")
    private int memoryIntentMaxSourceLength = 500;

    private final dev.langchain4j.model.chat.ChatModel chatModel;
    private final dev.langchain4j.model.chat.StreamingChatModel streamingChatModel;
    private final ConversationMemoryService conversationMemoryService;
    private final RagContextAssembler ragContextAssembler;
    private final RagRetrievalService ragRetrievalService;
    private final RagMemoryOrchestrator ragMemoryOrchestrator;
    private final RagStreamSessionManager ragStreamSessionManager;

    /**
     * 创建 RAG 问答服务并注入拆分后的协作组件。
     */
    @Autowired
    public RagService(
            dev.langchain4j.model.chat.ChatModel chatModel,
            dev.langchain4j.model.chat.StreamingChatModel streamingChatModel,
            ConversationMemoryService conversationMemoryService,
            RagContextAssembler ragContextAssembler,
            RagRetrievalService ragRetrievalService,
            RagMemoryOrchestrator ragMemoryOrchestrator,
            RagStreamSessionManager ragStreamSessionManager) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.conversationMemoryService = conversationMemoryService;
        this.ragContextAssembler = ragContextAssembler;
        this.ragRetrievalService = ragRetrievalService;
        this.ragMemoryOrchestrator = ragMemoryOrchestrator;
        this.ragStreamSessionManager = ragStreamSessionManager;
    }

    /**
     * 使用同步方式完成一次 RAG 问答。
     */
    public RagResponse ask(RagRequest request) {
        log.info("处理 RAG 问题: {}", request.question());

        try {
            String conversationId = ragStreamSessionManager.normalizeConversationId(request.conversationId());
            if (StringUtils.hasText(conversationId)) {
                ragStreamSessionManager.cancelGeneration(conversationId, "同步请求到达，取消已有流式生成");
            }

            ConversationMemoryService.ConversationMemorySnapshot memory = ragMemoryOrchestrator.getMemorySnapshot(conversationId);
            String rewrittenQuestion = ragContextAssembler.rewriteQuestion(
                request.question(),
                memory,
                memoryTopMatchMaxLength,
                memoryIntentMaxSourceLength
            );
            ragMemoryOrchestrator.updateIntentFromQuestion(conversationId, memory, request.question(), rewrittenQuestion);

            RagRetrievalResult retrievalResult = ragRetrievalService.retrieve(rewrittenQuestion, request);
            ragMemoryOrchestrator.updateFactsFromTopMatch(conversationId, retrievalResult.vectorMatches());

            if (retrievalResult.vectorMatches().isEmpty()) {
                ragMemoryOrchestrator.appendRoundMessages(conversationId, request.question(), EMPTY_MATCH_ANSWER);
                return new RagResponse(EMPTY_MATCH_ANSWER, conversationId, new ArrayList<>());
            }

            String prompt = ragContextAssembler.buildPrompt(memory, retrievalResult.context(), request.question());
            long answerStart = System.nanoTime();
            String answer = chatModel.chat(prompt);
            log.info("AI 基于知识库生成答案耗时: {} ms", elapsedMillis(answerStart));

            ragMemoryOrchestrator.appendRoundMessages(conversationId, request.question(), answer);
            return new RagResponse(answer, conversationId, retrievalResult.sources());
        } catch (Exception e) {
            log.error("RAG 处理失败", e);
            throw new RuntimeException("问题处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用流式方式处理一次 RAG 问答请求。
     */
    public SseEmitter askStream(RagRequest request) {
        if (request.question() == null || request.question().trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        String conversationId = ragStreamSessionManager.resolveConversationIdForStream(request.conversationId());
        ragStreamSessionManager.cancelGeneration(conversationId, "同会话新请求到达，取消旧请求");

        RagStreamSessionManager.InFlightGeneration generation = ragStreamSessionManager
            .createGeneration(conversationId, request.question(), streamTimeoutMs);
        ragStreamSessionManager.bindEmitterLifecycle(generation);
        ragStreamSessionManager.sendEvent(generation, "start", Map.of("conversationId", conversationId));
        ragStreamSessionManager.runAsync(() -> processStreamRequest(request, generation));
        return generation.emitter();
    }

    /**
     * 取消指定会话当前正在进行的生成任务。
     */
    public boolean cancelGeneration(String conversationId) {
        String normalizedConversationId = ragStreamSessionManager.normalizeConversationId(conversationId);
        if (!StringUtils.hasText(normalizedConversationId)) {
            return false;
        }
        return ragStreamSessionManager.cancelGeneration(normalizedConversationId, "用户主动取消");
    }

    /**
     * 在后台线程中执行流式问答主流程。
     */
    private void processStreamRequest(RagRequest request, RagStreamSessionManager.InFlightGeneration generation) {
        String conversationId = generation.conversationId();

        try {
            if (ragStreamSessionManager.shouldAbort(generation)) {
                return;
            }

            ConversationMemoryService.ConversationMemorySnapshot memory = ragMemoryOrchestrator.getMemorySnapshot(conversationId);
            String rewrittenQuestion = ragContextAssembler.rewriteQuestion(
                request.question(),
                memory,
                memoryTopMatchMaxLength,
                memoryIntentMaxSourceLength
            );
            ragMemoryOrchestrator.updateIntentFromQuestion(conversationId, memory, request.question(), rewrittenQuestion);
            if (ragStreamSessionManager.shouldAbort(generation)) {
                return;
            }

            RagRetrievalResult retrievalResult = ragRetrievalService.retrieve(rewrittenQuestion, request);
            ragMemoryOrchestrator.updateFactsFromTopMatch(conversationId, retrievalResult.vectorMatches());

            ragStreamSessionManager.sendEvent(generation, "sources", retrievalResult.sources());
            if (ragStreamSessionManager.shouldAbort(generation)) {
                return;
            }

            if (retrievalResult.matches().isEmpty()) {
                ragStreamSessionManager.sendEvent(generation, "delta", EMPTY_MATCH_ANSWER);
                ragMemoryOrchestrator.appendRoundMessages(conversationId, request.question(), EMPTY_MATCH_ANSWER);
                ragStreamSessionManager.sendEvent(generation, "complete", Map.of("conversationId", conversationId, "cancelled", false));
                ragStreamSessionManager.completeGeneration(generation);
                return;
            }

            String prompt = ragContextAssembler.buildPrompt(memory, retrievalResult.context(), request.question());
            streamingChatModel.chat(
                prompt,
                ragStreamSessionManager.createResponseHandler(
                    generation,
                    finalAnswer -> ragMemoryOrchestrator.appendRoundMessages(conversationId, generation.question(), finalAnswer),
                    error -> ragStreamSessionManager.completeWithError(generation, error)
                )
            );
        } catch (Exception e) {
            if (generation.isCancelled() || generation.isCompleted()) {
                ragStreamSessionManager.completeGeneration(generation);
                return;
            }
            log.error("流式 RAG 处理失败: conversationId={}", conversationId, e);
            ragStreamSessionManager.sendEvent(generation, "error", Map.of("message", ragStreamSessionManager.safeErrorMessage(e)));
            ragStreamSessionManager.completeWithError(generation, e);
        }
    }

    /**
     * 计算毫秒级耗时。
     */
    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}