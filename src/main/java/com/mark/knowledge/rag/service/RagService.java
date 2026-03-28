package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import com.mark.knowledge.rag.dto.SourceReference;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 问答服务。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Value("${rag.max-results:5}")
    private int maxResults;

    @Value("${rag.min-score:0.5}")
    private double minScore;

    @Value("${rag.rerank.candidate-multiplier:4}")
    private int rerankCandidateMultiplier;

    @Value("${rag.rerank.vector-weight:0.6}")
    private double vectorWeight;

    @Value("${rag.rerank.bm25-weight:0.4}")
    private double bm25Weight;

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ConversationMemoryService conversationMemoryService;
    private final Bm25Scorer bm25Scorer;

    public RagService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ConversationMemoryService conversationMemoryService,
            Bm25Scorer bm25Scorer) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.conversationMemoryService = conversationMemoryService;
        this.bm25Scorer = bm25Scorer;
    }

    public RagResponse ask(RagRequest request) {
        log.info("处理 RAG 问题: {}", request.question());

        try {
            String conversationId = request.conversationId();
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
                String emptyAnswer = "未在已上传文档中检索到足够相关的内容，请根据文档内容重新提问。";
                conversationMemoryService.appendUserMessage(conversationId, request.question());
                conversationMemoryService.appendAssistantMessage(conversationId, emptyAnswer);
                return new RagResponse(
                    emptyAnswer,
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

            List<SourceReference> sources = matches.stream()
                .map(match -> {
                    TextSegment segment = match.segment();
                    String filename = segment.metadata() != null
                        ? segment.metadata().getString("filename")
                        : "unknown";
                    return new SourceReference(filename, segment.text(), match.finalScore());
                })
                .collect(Collectors.toList());

            return new RagResponse(answer, conversationId, sources);
        } catch (Exception e) {
            log.error("RAG 处理失败", e);
            throw new RuntimeException("问题处理失败: " + e.getMessage(), e);
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private int resolveRequestedMaxResults(RagRequest request) {
        if (request.maxResults() == null || request.maxResults() < 1) {
            return maxResults;
        }
        return Math.min(request.maxResults(), maxResults);
    }

    private int resolveCandidateMaxResults(int requestedMaxResults) {
        int candidateMultiplier = Math.max(1, rerankCandidateMultiplier);
        return Math.max(requestedMaxResults, requestedMaxResults * candidateMultiplier);
    }

    private List<HybridMatch> rerankMatches(
            String query,
            List<EmbeddingMatch<TextSegment>> candidates,
            int limit) {
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

    private record HybridMatch(
        TextSegment segment,
        double semanticScore,
        double bm25Score,
        double finalScore
    ) {
    }
}
