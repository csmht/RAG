package com.mark.knowledge.rag.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagMemoryOrchestratorTest {

    @Mock
    private ChatModel chatModel;

    private ConversationMemoryService memoryService;
    private RagMemoryOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        memoryService = new ConversationMemoryService(3, 1800, 2000, 8, 200);
        orchestrator = new RagMemoryOrchestrator(chatModel, memoryService, new RagContextAssembler());
    }

    @Test
    void shouldUpdateIntentAndFacts() {
        when(chatModel.chat(any(String.class))).thenReturn("当前意图：定位上传限制");
        ConversationMemoryService.ConversationMemorySnapshot memory = new ConversationMemoryService.ConversationMemorySnapshot(
            "历史摘要",
            List.of("文件限制"),
            null,
            List.of(),
            Instant.now()
        );

        orchestrator.updateIntentFromQuestion("conv-1", memory, "为什么上传失败", "为什么上传失败");

        Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f});
        TextSegment segment = TextSegment.from("系统要求上传文件大小不能超过 50MB，且仅支持 pdf 文件。", Metadata.metadata("filename", "guide.txt"));
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.92, "m1", embedding, segment);
        orchestrator.updateFactsFromTopMatch("conv-1", List.of(match));

        ConversationMemoryService.ConversationMemorySnapshot snapshot = memoryService.getMemorySnapshot("conv-1");
        assertEquals("定位上传限制", snapshot.intent());
        assertEquals(List.of("系统要求上传文件大小不能超过 50MB，且仅支持 pdf 文件。"), snapshot.facts());
    }

    @Test
    void shouldAppendRoundMessagesAndTriggerCompression() {
        ReflectionTestUtils.setField(orchestrator, "memorySummaryEnabled", false);

        orchestrator.appendRoundMessages("conv-2", "问题", "回答");

        ConversationMemoryService.ConversationMemorySnapshot snapshot = memoryService.getMemorySnapshot("conv-2");
        assertEquals(2, snapshot.recentMessages().size());
        assertEquals("问题", snapshot.recentMessages().get(0).content());
        assertEquals("回答", snapshot.recentMessages().get(1).content());
        verify(chatModel, org.mockito.Mockito.never()).chat(any(String.class));
    }
}
