package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import com.mark.knowledge.rag.dto.SourceReference;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * RAG 问答服务。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String EMPTY_MATCH_ANSWER = "未在已上传文档中检索到足够相关的内容，请根据文档内容重新提问。";

    @Value("${rag.max-results:5}")
    private int maxResults;

    @Value("${rag.min-score:0.5}")
    private double minScore;

    @Value("${rag.stream-timeout-ms:300000}")
    private long streamTimeoutMs;

    @Value("${rag.rerank.candidate-multiplier:4}")
    private int rerankCandidateMultiplier;

    @Value("${rag.rerank.vector-weight:0.6}")
    private double vectorWeight;

    @Value("${rag.rerank.bm25-weight:0.4}")
    private double bm25Weight;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ConversationMemoryService conversationMemoryService;
    private final Bm25Scorer bm25Scorer;
    private final ConcurrentHashMap<String, InFlightGeneration> inFlightGenerations = new ConcurrentHashMap<>();

    public RagService(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ConversationMemoryService conversationMemoryService,
            Bm25Scorer bm25Scorer) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.conversationMemoryService = conversationMemoryService;
        this.bm25Scorer = bm25Scorer;
    }

    public RagResponse ask(RagRequest request) {
        log.info("处理 RAG 问题: {}", request.question());

        try {
            String conversationId = normalizeConversationId(request.conversationId());
            if (StringUtils.hasText(conversationId)) {
                cancelGenerationInternal(conversationId, "同步请求到达，取消已有流式生成");
            }

            List<ConversationMemoryService.ConversationMessage> history = conversationMemoryService
                .getRecentMessages(conversationId);
            String rewrittenQuestion = rewriteQuestion(request.question(), history);
            int requestedMaxResults = resolveRequestedMaxResults(request);

            long questionEmbeddingStart = System.nanoTime();
            var questionEmbedding = embeddingModel.embed(rewrittenQuestion).content();
            log.info("获取问题向量耗时: {} ms", elapsedMillis(questionEmbeddingStart));

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(resolveCandidateMaxResults(requestedMaxResults))
                .minScore(minScore)
                .build();

            log.info("问题向量维度: {}", questionEmbedding.dimension());

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> vectorMatches = searchResult.matches();

            log.info("向量检索召回 {} 条候选片段，最小分数阈值: {}", vectorMatches.size(), minScore);

            if (vectorMatches.isEmpty()) {
                conversationMemoryService.appendUserMessage(conversationId, request.question());
                conversationMemoryService.appendAssistantMessage(conversationId, EMPTY_MATCH_ANSWER);
                return new RagResponse(
                    EMPTY_MATCH_ANSWER,
                    conversationId,
                    new ArrayList<>()
                );
            }

            long rerankStart = System.nanoTime();
            List<HybridMatch> matches = rerankMatches(rewrittenQuestion, vectorMatches, requestedMaxResults);
            log.info("BM25 重排耗时: {} ms，最终保留 {} 条片段", elapsedMillis(rerankStart), matches.size());

            String context = matches.stream()
                .map(match -> match.segment().text())
                .collect(Collectors.joining("\n\n---\n\n"));

            String prompt = buildPrompt(history, context, request.question());
            long answerStart = System.nanoTime();
            String answer = chatModel.chat(prompt);
            log.info("AI 基于知识库生成答案耗时: {} ms", elapsedMillis(answerStart));

            conversationMemoryService.appendUserMessage(conversationId, request.question());
            conversationMemoryService.appendAssistantMessage(conversationId, answer);

            return new RagResponse(answer, conversationId, toSourceReferences(matches));
        } catch (Exception e) {
            log.error("RAG 处理失败", e);
            throw new RuntimeException("问题处理失败: " + e.getMessage(), e);
        }
    }

    public SseEmitter askStream(RagRequest request) {
        if (request.question() == null || request.question().trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        String conversationId = resolveConversationIdForStream(request.conversationId());
        cancelGenerationInternal(conversationId, "同会话新请求到达，取消旧请求");

        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        InFlightGeneration generation = new InFlightGeneration(
            UUID.randomUUID().toString(),
            conversationId,
            request.question(),
            emitter
        );
        inFlightGenerations.put(conversationId, generation);

        emitter.onCompletion(() -> {
            cleanupGeneration(generation);
            log.debug("SSE 连接完成: conversationId={}, requestId={}", conversationId, generation.requestId());
        });
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: conversationId={}, requestId={}", conversationId, generation.requestId());
            generation.markCancelled();
            generation.cancelHandle();
            sendEvent(generation, "cancelled", Map.of("conversationId", conversationId, "reason", "timeout"));
            completeGeneration(generation);
        });
        emitter.onError(error -> {
            log.warn("SSE 连接错误: conversationId={}, requestId={}, error={}",
                conversationId, generation.requestId(), error == null ? "unknown" : error.getMessage());
            generation.markCancelled();
            generation.cancelHandle();
            completeGeneration(generation);
        });

        sendEvent(generation, "start", Map.of("conversationId", conversationId));

        CompletableFuture.runAsync(() -> processStreamRequest(request, generation));
        return emitter;
    }

    private void processStreamRequest(RagRequest request, InFlightGeneration generation) {
        String conversationId = generation.conversationId();

        try {
            if (shouldAbort(generation)) {
                return;
            }

            List<ConversationMemoryService.ConversationMessage> history = conversationMemoryService
                .getRecentMessages(conversationId);
            String rewrittenQuestion = rewriteQuestion(request.question(), history);
            if (shouldAbort(generation)) {
                return;
            }

            int requestedMaxResults = resolveRequestedMaxResults(request);
            long questionEmbeddingStart = System.nanoTime();
            var questionEmbedding = embeddingModel.embed(rewrittenQuestion).content();
            log.info("流式获取问题向量耗时: conversationId={}, elapsedMs={}",
                conversationId, elapsedMillis(questionEmbeddingStart));
            if (shouldAbort(generation)) {
                return;
            }

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(resolveCandidateMaxResults(requestedMaxResults))
                .minScore(minScore)
                .build();

            log.info("流式问题向量维度: conversationId={}, dimension={}", conversationId, questionEmbedding.dimension());

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> vectorMatches = searchResult.matches();
            log.info("流式向量检索召回: conversationId={}, matches={}, minScore={}",
                conversationId, vectorMatches.size(), minScore);

            long rerankStart = System.nanoTime();
            List<HybridMatch> matches = rerankMatches(rewrittenQuestion, vectorMatches, requestedMaxResults);
            log.info("流式 BM25 重排耗时: conversationId={}, elapsedMs={}, retained={}",
                conversationId, elapsedMillis(rerankStart), matches.size());

            sendEvent(generation, "sources", toSourceReferences(matches));
            if (shouldAbort(generation)) {
                return;
            }

            if (matches.isEmpty()) {
                sendEvent(generation, "delta", EMPTY_MATCH_ANSWER);
                conversationMemoryService.appendUserMessage(conversationId, request.question());
                conversationMemoryService.appendAssistantMessage(conversationId, EMPTY_MATCH_ANSWER);
                sendEvent(generation, "complete", Map.of("conversationId", conversationId, "cancelled", false));
                completeGeneration(generation);
                return;
            }

            String context = matches.stream()
                .map(match -> match.segment().text())
                .collect(Collectors.joining("\n\n---\n\n"));
            String prompt = buildPrompt(history, context, request.question());
            streamingChatModel.chat(prompt, new RagStreamingResponseHandler(generation, conversationId));
        } catch (Exception e) {
            if (generation.isCancelled() || generation.isCompleted()) {
                completeGeneration(generation);
                return;
            }
            log.error("流式 RAG 处理失败: conversationId={}", conversationId, e);
            sendEvent(generation, "error", Map.of("message", safeErrorMessage(e)));
            completeWithError(generation, e);
        }
    }

    public boolean cancelGeneration(String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        if (!StringUtils.hasText(normalizedConversationId)) {
            return false;
        }
        return cancelGenerationInternal(normalizedConversationId, "用户主动取消");
    }

    private boolean cancelGenerationInternal(String conversationId, String reason) {
        InFlightGeneration generation = inFlightGenerations.get(conversationId);
        if (generation == null) {
            return false;
        }
        if (!generation.markCancelled()) {
            return false;
        }

        log.info("取消流式生成: conversationId={}, requestId={}, reason={}", conversationId, generation.requestId(), reason);
        generation.cancelHandle();
        sendEvent(generation, "cancelled", Map.of("conversationId", conversationId, "reason", reason));
        completeGeneration(generation);
        return true;
    }

    private void completeGeneration(InFlightGeneration generation) {
        if (!generation.markCompleted()) {
            return;
        }
        cleanupGeneration(generation);
        try {
            generation.emitter().complete();
        } catch (Exception e) {
            log.debug("结束 SSE 连接时忽略异常: conversationId={}, requestId={}, message={}",
                generation.conversationId(), generation.requestId(), e.getMessage());
        }
    }

    private void completeWithError(InFlightGeneration generation, Throwable error) {
        if (!generation.markCompleted()) {
            return;
        }
        cleanupGeneration(generation);
        try {
            generation.emitter().completeWithError(error);
        } catch (Exception e) {
            log.debug("结束异常 SSE 连接时忽略异常: conversationId={}, requestId={}, message={}",
                generation.conversationId(), generation.requestId(), e.getMessage());
        }
    }

    private void cleanupGeneration(InFlightGeneration generation) {
        inFlightGenerations.compute(generation.conversationId(), (conversationId, current) -> {
            if (current == null) {
                return null;
            }
            return generation.requestId().equals(current.requestId()) ? null : current;
        });
    }

    private void sendEvent(InFlightGeneration generation, String eventName, Object data) {
        if (generation.isCompleted()) {
            return;
        }
        try {
            generation.emitter().send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("发送 SSE 事件失败: conversationId={}, requestId={}, event={}",
                generation.conversationId(), generation.requestId(), eventName);
            generation.markCancelled();
            generation.cancelHandle();
            completeGeneration(generation);
        }
    }

    private boolean shouldAbort(InFlightGeneration generation) {
        return generation.isCancelled()
            || generation.isCompleted()
            || inFlightGenerations.get(generation.conversationId()) != generation;
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private int resolveRequestedMaxResults(RagRequest request) {
        int configuredMaxResults = Math.max(1, maxResults);
        if (request.maxResults() == null || request.maxResults() < 1) {
            return configuredMaxResults;
        }
        return Math.min(request.maxResults(), configuredMaxResults);
    }

    private int resolveCandidateMaxResults(int requestedMaxResults) {
        int candidateMultiplier = Math.max(1, rerankCandidateMultiplier);
        return Math.max(requestedMaxResults, requestedMaxResults * candidateMultiplier);
    }

    private List<HybridMatch> rerankMatches(
            String query,
            List<EmbeddingMatch<TextSegment>> candidates,
            int limit) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> candidateTexts = candidates.stream()
            .map(match -> match.embedded().text())
            .collect(Collectors.toList());
        List<Double> bm25Scores = bm25Scorer.score(query, candidateTexts);
        List<Double> normalizedVectorScores = normalizeScores(candidates.stream()
            .map(EmbeddingMatch::score)
            .collect(Collectors.toList()));
        List<Double> normalizedBm25Scores = normalizeScores(bm25Scores);

        double safeVectorWeight = Math.max(0.0, vectorWeight);
        double safeBm25Weight = Math.max(0.0, bm25Weight);
        double totalWeight = safeVectorWeight + safeBm25Weight;
        double normalizedVectorWeight = totalWeight == 0.0 ? 0.5 : safeVectorWeight / totalWeight;
        double normalizedBm25Weight = totalWeight == 0.0 ? 0.5 : safeBm25Weight / totalWeight;

        List<HybridMatch> rerankedMatches = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            EmbeddingMatch<TextSegment> candidate = candidates.get(i);
            double finalScore = normalizedVectorScores.get(i) * normalizedVectorWeight
                + normalizedBm25Scores.get(i) * normalizedBm25Weight;
            rerankedMatches.add(new HybridMatch(
                candidate.embedded(),
                candidate.score(),
                bm25Scores.get(i),
                finalScore
            ));
        }

        Comparator<HybridMatch> scoreComparator = Comparator
            .comparingDouble(HybridMatch::finalScore).reversed()
            .thenComparing(Comparator.comparingDouble(HybridMatch::semanticScore).reversed())
            .thenComparing(Comparator.comparingDouble(HybridMatch::bm25Score).reversed());

        return rerankedMatches.stream()
            .sorted(scoreComparator)
            .limit(limit)
            .collect(Collectors.toList());
    }

    private List<Double> normalizeScores(List<Double> scores) {
        if (scores.isEmpty()) {
            return List.of();
        }

        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        if (Double.compare(max, min) == 0) {
            double normalizedValue = max > 0.0 ? 1.0 : 0.0;
            return scores.stream()
                .map(score -> normalizedValue)
                .collect(Collectors.toList());
        }

        double range = max - min;
        return scores.stream()
            .map(score -> (score - min) / range)
            .collect(Collectors.toList());
    }

    private String rewriteQuestion(String question, List<ConversationMemoryService.ConversationMessage> history) {
        if (history.isEmpty()) {
            return question;
        }

        String historyText = formatHistory(history);
        String rewritePrompt = String.format("""
            你需要结合历史对话，把用户当前问题改写成一个完整、独立、可用于知识库检索的问题。
            如果当前问题本身已经完整，直接原样返回，不要增加解释。
            只输出改写后的问题，不要输出其它内容。

            历史对话：
            %s

            当前问题：
            %s
            """, historyText, question);

        String rewritten = chatModel.chat(rewritePrompt);
        return rewritten != null && !rewritten.isBlank() ? rewritten.trim() : question;
    }

    private String buildPrompt(
            List<ConversationMemoryService.ConversationMessage> history,
            String context,
            String question) {
        String historyText = history.isEmpty() ? "无" : formatHistory(history);

        return String.format("""
            你是一个基于文档内容回答问题的助手。
            你必须严格依据下面提供的历史对话和文档上下文回答。
            如果上下文中没有答案，请明确说明“根据已上传文档无法回答该问题”。
            不要编造，不要补充上下文之外的事实。

            历史对话：
            %s

            文档上下文：
            %s

            用户当前问题：%s

            请直接给出中文答案：""", historyText, context, question);
    }

    private String formatHistory(List<ConversationMemoryService.ConversationMessage> history) {
        return history.stream()
            .map(message -> (message.role() == ConversationMemoryService.ConversationRole.USER ? "用户：" : "助手：")
                + message.content())
            .collect(Collectors.joining("\n"));
    }

    private List<SourceReference> toSourceReferences(List<HybridMatch> matches) {
        return matches.stream()
            .map(match -> {
                TextSegment segment = match.segment();
                String filename = segment.metadata() != null
                    ? segment.metadata().getString("filename")
                    : "unknown";
                return new SourceReference(filename, segment.text(), match.finalScore());
            })
            .collect(Collectors.toList());
    }

    private String resolveConversationIdForStream(String conversationId) {
        String normalized = normalizeConversationId(conversationId);
        if (StringUtils.hasText(normalized)) {
            return normalized;
        }
        return "rag-" + UUID.randomUUID();
    }

    private String normalizeConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        return conversationId.trim();
    }

    private String safeErrorMessage(Throwable error) {
        if (error == null || !StringUtils.hasText(error.getMessage())) {
            return "未知错误";
        }
        return error.getMessage();
    }

    private final class RagStreamingResponseHandler implements StreamingChatResponseHandler {

        private final InFlightGeneration generation;
        private final String conversationId;

        private RagStreamingResponseHandler(InFlightGeneration generation, String conversationId) {
            this.generation = generation;
            this.conversationId = conversationId;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            handlePartialText(partialResponse);
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            if (context != null) {
                generation.captureHandle(context.streamingHandle());
            }
            if (partialResponse != null) {
                handlePartialText(partialResponse.text());
            }
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            if (generation.isCompleted()) {
                return;
            }

            String finalAnswer = generation.answer();
            if (response != null && response.aiMessage() != null && StringUtils.hasText(response.aiMessage().text())) {
                finalAnswer = response.aiMessage().text();
            }

            if (!generation.isCancelled()) {
                if (StringUtils.hasText(finalAnswer)) {
                    conversationMemoryService.appendUserMessage(conversationId, generation.question());
                    conversationMemoryService.appendAssistantMessage(conversationId, finalAnswer);
                }
                sendEvent(generation, "complete", Map.of("conversationId", conversationId, "cancelled", false));
            } else {
                sendEvent(generation, "complete", Map.of("conversationId", conversationId, "cancelled", true));
            }
            completeGeneration(generation);
        }

        @Override
        public void onError(Throwable error) {
            if (generation.isCancelled()) {
                sendEvent(generation, "complete", Map.of("conversationId", conversationId, "cancelled", true));
                completeGeneration(generation);
                return;
            }

            log.error("流式模型响应失败: conversationId={}, requestId={}", conversationId, generation.requestId(), error);
            sendEvent(generation, "error", Map.of("message", safeErrorMessage(error)));
            completeWithError(generation, error);
        }

        private void handlePartialText(String text) {
            if (!StringUtils.hasText(text) || generation.isCancelled() || generation.isCompleted()) {
                return;
            }
            generation.appendAnswer(text);
            sendEvent(generation, "delta", text);
        }
    }

    private static final class InFlightGeneration {
        private final String requestId;
        private final String conversationId;
        private final String question;
        private final SseEmitter emitter;
        private final AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final StringBuilder answerBuilder = new StringBuilder();

        private InFlightGeneration(String requestId, String conversationId, String question, SseEmitter emitter) {
            this.requestId = requestId;
            this.conversationId = conversationId;
            this.question = question;
            this.emitter = emitter;
        }

        private String requestId() {
            return requestId;
        }

        private String conversationId() {
            return conversationId;
        }

        private String question() {
            return question;
        }

        private SseEmitter emitter() {
            return emitter;
        }

        private void captureHandle(StreamingHandle handle) {
            if (handle == null) {
                return;
            }

            StreamingHandle previous = handleRef.getAndSet(handle);
            if (previous != null && previous != handle && !previous.isCancelled()) {
                previous.cancel();
            }

            if (isCancelled() || isCompleted()) {
                handle.cancel();
            }
        }

        private void cancelHandle() {
            StreamingHandle handle = handleRef.get();
            if (handle == null) {
                return;
            }
            try {
                handle.cancel();
            } catch (Exception ignored) {
            }
        }

        private boolean markCancelled() {
            return cancelled.compareAndSet(false, true);
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private boolean markCompleted() {
            return completed.compareAndSet(false, true);
        }

        private boolean isCompleted() {
            return completed.get();
        }

        private void appendAnswer(String text) {
            synchronized (answerBuilder) {
                answerBuilder.append(text);
            }
        }

        private String answer() {
            synchronized (answerBuilder) {
                return answerBuilder.toString();
            }
        }
    }

    private record HybridMatch(
        TextSegment segment,
        double semanticScore,
        double bm25Score,
        double finalScore
    ) {
    }
}
