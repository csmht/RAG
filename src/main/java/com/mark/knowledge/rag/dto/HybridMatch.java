package com.mark.knowledge.rag.dto;

import dev.langchain4j.data.segment.TextSegment;

/**
 * 混合检索重排后的片段结果。
 */
public record HybridMatch(
    TextSegment segment,
    double semanticScore,
    double bm25Score,
    double finalScore
) {
}
