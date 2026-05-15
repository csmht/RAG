package com.mark.knowledge.rag.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            new ConversationMemoryService(3, 1800),
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
}
