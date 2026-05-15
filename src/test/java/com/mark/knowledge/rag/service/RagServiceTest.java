package com.mark.knowledge.rag.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private StreamingChatModel streamingChatModel;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(
            chatModel,
            streamingChatModel,
            embeddingModel,
            embeddingStore,
            new ConversationMemoryService(3, 1800, 2000, 8, 200),
            new Bm25Scorer()
        );
    }

    @Test
    void shouldBuildPromptWithStructuredMemorySections() throws Exception {
        ConversationMemoryService.ConversationMemorySnapshot memory =
            new ConversationMemoryService.ConversationMemorySnapshot(
                "用户之前在排查上传失败问题",
                List.of("接口是 /api/rag/upload", "文件类型是 pdf"),
                "定位上传失败原因",
                List.of(
                    new ConversationMemoryService.ConversationMessage(
                        ConversationMemoryService.ConversationRole.USER,
                        "为什么上传失败",
                        Instant.now()
                    ),
                    new ConversationMemoryService.ConversationMessage(
                        ConversationMemoryService.ConversationRole.ASSISTANT,
                        "请检查文件格式和大小",
                        Instant.now()
                    )
                ),
                Instant.now()
            );

        String prompt = invokeBuildPrompt(memory, "文档片段 A", "现在应该先检查什么");

        assertTrue(prompt.contains("当前意图：\n定位上传失败原因"));
        assertTrue(prompt.contains("已确认事实：\n- 接口是 /api/rag/upload\n- 文件类型是 pdf"));
        assertTrue(prompt.contains("历史摘要：\n用户之前在排查上传失败问题"));
        assertTrue(prompt.contains("最近对话：\n用户：为什么上传失败\n助手：请检查文件格式和大小"));
        assertTrue(prompt.contains("文档上下文：\n文档片段 A"));
    }

    @Test
    void shouldFallbackToEmptyMemoryWithoutNullMarkers() throws Exception {
        ConversationMemoryService.ConversationMemorySnapshot memory =
            new ConversationMemoryService.ConversationMemorySnapshot(
                null,
                List.of(),
                null,
                List.of(),
                null
            );

        String prompt = invokeBuildPrompt(memory, "文档片段 B", "这个问题怎么回答");

        assertTrue(prompt.contains("会话记忆：\n无"));
        assertFalse(prompt.contains("null"));
        assertFalse(prompt.contains("当前意图："));
        assertFalse(prompt.contains("已确认事实："));
        assertFalse(prompt.contains("历史摘要："));
        assertFalse(prompt.contains("最近对话："));
    }

    @Test
    void shouldUpdateIntentAndFactsDuringAskFlow() {
        Metadata metadata = Metadata.metadata("filename", "guide.txt");
        TextSegment segment = TextSegment.from("系统要求上传文件大小不能超过 50MB，且仅支持 pdf 文件。", metadata);
        Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f});
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.92, "m1", embedding, segment);

        when(chatModel.chat(any(String.class)))
            .thenReturn("定位上传限制")
            .thenReturn("文件大小不能超过 50MB\n仅支持 pdf 文件")
            .thenReturn("请先检查文件大小与格式限制");
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(embedding));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
            .thenReturn(new EmbeddingSearchResult<>(List.of(match)));

        RagResponse response = ragService.ask(new RagRequest("为什么上传失败", "conv-1", 3));
        assertEquals("请先检查文件大小与格式限制", response.answer());

        ConversationMemoryService memoryService = extractMemoryService();
        ConversationMemoryService.ConversationMemorySnapshot snapshot = memoryService.getMemorySnapshot("conv-1");
        assertEquals("定位上传限制", snapshot.intent());
        assertEquals(List.of("文件大小不能超过 50MB", "仅支持 pdf 文件"), snapshot.facts());
        assertEquals(2, snapshot.recentMessages().size());
    }

    private String invokeBuildPrompt(
            ConversationMemoryService.ConversationMemorySnapshot memory,
            String context,
            String question) throws Exception {
        Method method = RagService.class.getDeclaredMethod(
            "buildPrompt",
            ConversationMemoryService.ConversationMemorySnapshot.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        return (String) method.invoke(ragService, memory, context, question);
    }

    private ConversationMemoryService extractMemoryService() {
        try {
            var field = RagService.class.getDeclaredField("conversationMemoryService");
            field.setAccessible(true);
            return (ConversationMemoryService) field.get(ragService);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
