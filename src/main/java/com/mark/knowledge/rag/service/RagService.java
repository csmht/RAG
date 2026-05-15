package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import com.mark.knowledge.rag.dto.SourceReference;
import dev.langchain4j.data.document.Metadata;
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
    private int maxResults = 5;

    @Value("${rag.min-score:0.5}")
    private double minScore = 0.5;

    @Value("${rag.stream-timeout-ms:300000}")
    private long streamTimeoutMs = 300000L;

    @Value("${rag.summary-compress-threshold-ratio:1.0}")
    private double summaryCompressThresholdRatio = 1.0;

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

    @Value("${rag.rerank.candidate-multiplier:4}")
    private int rerankCandidateMultiplier = 4;

    @Value("${rag.rerank.vector-weight:0.6}")
    private double vectorWeight = 0.6;

    @Value("${rag.rerank.bm25-weight:0.4}")
    private double bm25Weight = 0.4;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ConversationMemoryService conversationMemoryService;
    private final Bm25Scorer bm25Scorer;
    private final ConcurrentHashMap<String, InFlightGeneration> inFlightGenerations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> summaryCompressionStates = new ConcurrentHashMap<>();

    /**
     * 创建 RAG 问答服务并注入其依赖组件。
     */
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

    /**
     * 使用同步方式完成一次 RAG 问答。
     */
    public RagResponse ask(RagRequest request) {
        log.info("处理 RAG 问题: {}", request.question());

        try {
            String conversationId = normalizeConversationId(request.conversationId());
            if (StringUtils.hasText(conversationId)) {
                cancelGenerationInternal(conversationId, "同步请求到达，取消已有流式生成");
            }

            ConversationMemoryService.ConversationMemorySnapshot memory = conversationMemoryService
                .getMemorySnapshot(conversationId);
            String rewrittenQuestion = rewriteQuestion(request.question(), memory);
            updateIntentFromQuestion(conversationId, memory, request.question(), rewrittenQuestion);
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
            updateFactsFromTopMatch(conversationId, request.question(), vectorMatches);

            log.info("向量检索召回 {} 条候选片段，最小分数阈值: {}", vectorMatches.size(), minScore);

            if (vectorMatches.isEmpty()) {
                conversationMemoryService.appendUserMessage(conversationId, request.question());
                triggerAsyncSummaryCompression(conversationId);
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

            String prompt = buildPrompt(memory, context, request.question());
            long answerStart = System.nanoTime();
            String answer = chatModel.chat(prompt);
            log.info("AI 基于知识库生成答案耗时: {} ms", elapsedMillis(answerStart));

            conversationMemoryService.appendUserMessage(conversationId, request.question());
            triggerAsyncSummaryCompression(conversationId);
            conversationMemoryService.appendAssistantMessage(conversationId, answer);

            return new RagResponse(answer, conversationId, toSourceReferences(matches));
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

    /**
     * 在后台线程中执行流式问答主流程。
     */
    private void processStreamRequest(RagRequest request, InFlightGeneration generation) {
        String conversationId = generation.conversationId();

        try {
            if (shouldAbort(generation)) {
                return;
            }

            ConversationMemoryService.ConversationMemorySnapshot memory = conversationMemoryService
                .getMemorySnapshot(conversationId);
            String rewrittenQuestion = rewriteQuestion(request.question(), memory);
            updateIntentFromQuestion(conversationId, memory, request.question(), rewrittenQuestion);
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
            updateFactsFromTopMatch(conversationId, request.question(), vectorMatches);
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
                triggerAsyncSummaryCompression(conversationId);
                conversationMemoryService.appendAssistantMessage(conversationId, EMPTY_MATCH_ANSWER);
                sendEvent(generation, "complete", Map.of("conversationId", conversationId, "cancelled", false));
                completeGeneration(generation);
                return;
            }

            String context = matches.stream()
                .map(match -> match.segment().text())
                .collect(Collectors.joining("\n\n---\n\n"));
            String prompt = buildPrompt(memory, context, request.question());
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

    /**
     * 取消指定会话当前正在进行的生成任务。
     */
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

    /**
     * 结合当前会话记忆改写用户问题，生成更适合检索的完整问题。
     */
    private String rewriteQuestion(String question, ConversationMemoryService.ConversationMemorySnapshot memory) {
        if (memory == null) {
            return question;
        }

        String memoryContext = buildMemoryContext(memory);
        if (!StringUtils.hasText(memoryContext)) {
            return question;
        }

        String rewritePrompt = String.format("""
            你需要结合会话记忆，把用户当前问题改写成一个完整、独立、可用于知识库检索的问题。
            如果当前问题本身已经完整，直接原样返回，不要增加解释。
            只输出改写后的问题，不要输出其它内容。

            会话记忆：
            %s

            当前问题：
            %s
            """, memoryContext, question);

        String rewritten = chatModel.chat(rewritePrompt);
        return rewritten != null && !rewritten.isBlank() ? rewritten.trim() : question;
    }

    /**
     * 构建最终回答阶段使用的 Prompt。
     */
    private String buildPrompt(
            ConversationMemoryService.ConversationMemorySnapshot memory,
            String context,
            String question) {
        String memoryContext = buildMemoryContext(memory);
        String safeMemoryContext = StringUtils.hasText(memoryContext) ? memoryContext : "无";

        return String.format("""
            你是一个基于文档内容回答问题的助手。
            你必须严格依据下面提供的会话记忆和文档上下文回答。
            如果上下文中没有答案，请明确说明“根据已上传文档无法回答该问题”。
            不要编造，不要补充上下文之外的事实。

            会话记忆：
            %s

            文档上下文：
            %s

            用户当前问题：%s

            请直接给出中文答案：""", safeMemoryContext, context, question);
    }

    /**
     * 根据原始问题与改写问题更新会话意图。
     */
    private void updateIntentFromQuestion(
            String conversationId,
            ConversationMemoryService.ConversationMemorySnapshot memory,
            String question,
            String rewrittenQuestion) {
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
     * 提取当前问题对应的阶段性意图描述。
     */
    private String extractIntentFromQuestion(
            ConversationMemoryService.ConversationMemorySnapshot memory,
            String question,
            String rewrittenQuestion) {
        String prompt = buildIntentExtractionPrompt(memory, question, rewrittenQuestion);
        String response = chatModel.chat(prompt);
        return response == null ? null : response.trim();
    }

    /**
     * 构建意图提取使用的 Prompt。
     */
    private String buildIntentExtractionPrompt(
            ConversationMemoryService.ConversationMemorySnapshot memory,
            String question,
            String rewrittenQuestion) {
        String memoryContext = buildMemoryContext(memory);
        String safeMemoryContext = StringUtils.hasText(memoryContext)
            ? trimToMaxLength(memoryContext, memoryIntentMaxSourceLength)
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
        if (List.of("无", "未知", "未提及", "无法判断").contains(normalized)) {
            return null;
        }
        return normalized;
    }

    /**
     * 根据最高相关检索片段更新会话事实。
     */
    private void updateFactsFromTopMatch(
            String conversationId,
            String question,
            List<EmbeddingMatch<TextSegment>> matches) {
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
        List<String> extractedFacts = extractFactsFromMatch(question, topMatch);
        List<String> normalizedFacts = normalizeExtractedFacts(extractedFacts);
        if (!normalizedFacts.isEmpty()) {
            conversationMemoryService.updateFacts(conversationId, normalizedFacts);
        }
    }

    /**
     * 从最高相关片段中提取稳定事实列表。
     */
    private List<String> extractFactsFromMatch(String question, EmbeddingMatch<TextSegment> topMatch) {
        TextSegment segment = topMatch.embedded();
        if (segment == null || !StringUtils.hasText(segment.text())) {
            return List.of();
        }
        String prompt = String.format("""
            你需要根据用户问题和最相关的文档片段，提取 1 到 %d 条稳定事实。
            每条事实单独一行输出，不要编号，不要解释，不要输出无关内容。

            用户问题：
            %s

            文档片段：
            %s
            """, Math.max(memoryMaxFactsPerUpdate, 1), question, trimToMaxLength(segment.text(), memoryTopMatchMaxLength));
        String response = chatModel.chat(prompt);
        if (!StringUtils.hasText(response)) {
            return List.of();
        }
        return response.lines().map(String::trim).filter(StringUtils::hasText).toList();
    }

    /**
     * 清洗事实提取结果，过滤无效或占位内容。
     */
    private List<String> normalizeExtractedFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<String> invalidMarkers = List.of("无", "未知", "未提及", "无法判断");
        List<String> normalized = new ArrayList<>();
        for (String fact : facts) {
            if (!StringUtils.hasText(fact)) {
                continue;
            }
            String value = fact.trim()
                .replaceFirst("^[\\-•*\\d.、\s]+", "")
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
     * 异步触发指定会话的历史摘要压缩任务。
     */
    private void triggerAsyncSummaryCompression(String conversationId) {
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
     * 在后台线程中压缩指定会话的历史摘要。
     */
    private void compressSummaryAsync(String conversationId, AtomicBoolean state) {
        try {
            String source = conversationMemoryService.getSummarySource(conversationId);
            if (!StringUtils.hasText(source)) {
                return;
            }
            String summary = memorySummaryModelEnabled
                ? summarizeHistory(source)
                : trimToMaxLength(source, memorySummarySourceMaxLength);
            if (StringUtils.hasText(summary)) {
                conversationMemoryService.updateSummary(conversationId, summary);
            }
        } catch (Exception e) {
            log.warn("异步压缩历史摘要失败: conversationId={}, message={}", conversationId, e.getMessage());
        } finally {
            state.set(false);
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
     * 构建历史摘要压缩使用的 Prompt。
     */
    private String buildSummaryCompressionPrompt(String source) {
        return String.format("""
            你需要将以下会话历史压缩为一段简洁、稳定、可复用的中文历史摘要。
            保留关键信息、主要结论、长期有效约束，去掉重复与冗余表述。
            只输出摘要正文，不要加标题，不要解释。

            会话历史：
            %s
            """, trimToMaxLength(source, memorySummarySourceMaxLength));
    }

    /**
     * 按最大长度截断文本，优先保留尾部最新内容。
     */
    private String trimToMaxLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        int safeMaxLength = Math.max(maxLength, 1);
        if (value.length() <= safeMaxLength) {
            return value;
        }
        return value.substring(value.length() - safeMaxLength);
    }

    /**
     * 将结构化记忆整理为可注入 Prompt 的标准文本块。
     */
    private String buildMemoryContext(ConversationMemoryService.ConversationMemorySnapshot memory) {
        if (memory == null) {
            return "";
        }

        List<String> sections = new ArrayList<>();
        if (StringUtils.hasText(memory.intent())) {
            sections.add("当前意图：\n" + memory.intent());
        }
        if (memory.facts() != null && !memory.facts().isEmpty()) {
            sections.add("已确认事实：\n" + memory.facts().stream()
                .map(fact -> "- " + fact)
                .collect(Collectors.joining("\n")));
        }
        if (StringUtils.hasText(memory.summary())) {
            sections.add("历史摘要：\n" + memory.summary());
        }
        if (memory.recentMessages() != null && !memory.recentMessages().isEmpty()) {
            sections.add("最近对话：\n" + formatHistory(memory.recentMessages()));
        }
        return String.join("\n\n", sections);
    }

    /**
     * 将最近对话格式化为可读的角色文本。
     */
    private String formatHistory(List<ConversationMemoryService.ConversationMessage> history) {
        return history.stream()
            .map(message -> (message.role() == ConversationMemoryService.ConversationRole.USER ? "用户：" : "助手：")
                + message.content())
            .collect(Collectors.joining("\n"));
    }

    /**
     * 将检索命中结果转换为接口返回使用的来源引用列表。
     */
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
                    triggerAsyncSummaryCompression(conversationId);
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
