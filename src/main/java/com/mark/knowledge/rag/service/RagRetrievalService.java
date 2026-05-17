package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.HybridMatch;
import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagRetrievalResult;
import com.mark.knowledge.rag.dto.SourceReference;
import dev.langchain4j.data.segment.TextSegment;
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
 * RAG 检索与重排服务。
 */
@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    @Value("${rag.max-results:5}")
    private int maxResults = 5;

    @Value("${rag.min-score:0.5}")
    private double minScore = 0.5;

    @Value("${rag.rerank.candidate-multiplier:4}")
    private int rerankCandidateMultiplier = 4;

    @Value("${rag.rerank.vector-weight:0.6}")
    private double vectorWeight = 0.6;

    @Value("${rag.rerank.bm25-weight:0.4}")
    private double bm25Weight = 0.4;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Bm25Scorer bm25Scorer;

    /**
     * 创建检索与重排服务。
     */
    public RagRetrievalService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            Bm25Scorer bm25Scorer) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.bm25Scorer = bm25Scorer;
    }

    /**
     * 执行一次完整的检索与重排流程。
     */
    public RagRetrievalResult retrieve(String rewrittenQuestion, RagRequest request) {
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

        long rerankStart = System.nanoTime();
        List<HybridMatch> matches = rerankMatches(rewrittenQuestion, vectorMatches, requestedMaxResults);
        log.info("BM25 重排耗时: {} ms，最终保留 {} 条片段", elapsedMillis(rerankStart), matches.size());

        String context = matches.stream()
            .map(match -> match.segment().text())
            .collect(Collectors.joining("\n\n---\n\n"));

        return new RagRetrievalResult(
            rewrittenQuestion,
            context,
            vectorMatches,
            matches,
            toSourceReferences(matches)
        );
    }

    /**
     * 解析对外请求需要的最终片段数量。
     */
    public int resolveRequestedMaxResults(RagRequest request) {
        int configuredMaxResults = Math.max(1, maxResults);
        if (request.maxResults() == null || request.maxResults() < 1) {
            return configuredMaxResults;
        }
        return Math.min(request.maxResults(), configuredMaxResults);
    }

    /**
     * 解析参与 BM25 重排的候选片段数量。
     */
    public int resolveCandidateMaxResults(int requestedMaxResults) {
        int safeRequestedMaxResults = Math.max(1, requestedMaxResults);
        int candidateMultiplier = Math.max(1, rerankCandidateMultiplier);
        return Math.max(safeRequestedMaxResults, safeRequestedMaxResults * candidateMultiplier);
    }

    /**
     * 将检索命中结果转换为接口返回使用的来源引用列表。
     */
    public List<SourceReference> toSourceReferences(List<HybridMatch> matches) {
        return matches.stream()
            .map(match -> {
                TextSegment segment = match.segment();
                String filename = segment.metadata() != null
                    ? segment.metadata().getString("filename")
                    : RagTextSupport.UNKNOWN_FILENAME;
                return new SourceReference(filename, segment.text(), match.finalScore());
            })
            .collect(Collectors.toList());
    }

    /**
     * 按混合分数对候选结果做重排与截断。
     */
    public List<HybridMatch> rerankMatches(String query, List<EmbeddingMatch<TextSegment>> candidates, int limit) {
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

    /**
     * 将一组分数归一化到 0 到 1 之间。
     */
    public List<Double> normalizeScores(List<Double> scores) {
        if (scores.isEmpty()) {
            return List.of();
        }

        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        if (Double.compare(max, min) == 0) {
            double normalizedValue = max > 0.0 ? 1.0 : 0.0;
            return scores.stream().map(score -> normalizedValue).collect(Collectors.toList());
        }

        double range = max - min;
        return scores.stream().map(score -> (score - min) / range).collect(Collectors.toList());
    }

    /**
     * 计算毫秒级耗时。
     */
    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
