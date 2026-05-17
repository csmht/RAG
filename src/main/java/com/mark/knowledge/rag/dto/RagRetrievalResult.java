package com.mark.knowledge.rag.dto;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

/**
 * RAG 检索阶段的标准结果对象。
 */
public record RagRetrievalResult(
    String rewrittenQuestion,
    String context,
    List<EmbeddingMatch<TextSegment>> vectorMatches,
    List<HybridMatch> matches,
    List<SourceReference> sources
) {
}
