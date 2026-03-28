package com.mark.knowledge.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25ScorerTest {

    private final Bm25Scorer bm25Scorer = new Bm25Scorer();

    @Test
    void shouldPreferDocumentWithMatchingChineseKeywords() {
        List<Double> scores = bm25Scorer.score(
            "向量检索 bm25 重排",
            List.of(
                "向量检索可以结合 BM25 重排来提升知识库问答效果。",
                "这段内容主要介绍天气、旅游和做饭技巧。"
            )
        );

        assertTrue(scores.getFirst() > scores.get(1));
    }

    @Test
    void shouldReturnZeroScoresForBlankQuery() {
        List<Double> scores = bm25Scorer.score(
            "   ",
            List.of("任意文档内容")
        );

        assertEquals(List.of(0.0), scores);
    }
}
