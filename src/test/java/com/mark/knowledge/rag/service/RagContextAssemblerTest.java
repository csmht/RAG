package com.mark.knowledge.rag.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagContextAssemblerTest {

    private final RagContextAssembler assembler = new RagContextAssembler();

    @Test
    void shouldBuildPromptWithStructuredMemorySections() {
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

        String prompt = assembler.buildPrompt(memory, "文档片段 A", "现在应该先检查什么");

        assertTrue(prompt.contains("当前意图：\n定位上传失败原因"));
        assertTrue(prompt.contains("已确认事实：\n- 接口是 /api/rag/upload\n- 文件类型是 pdf"));
        assertTrue(prompt.contains("历史摘要：\n用户之前在排查上传失败问题"));
        assertTrue(prompt.contains("最近对话：\n用户：为什么上传失败\n助手：请检查文件格式和大小"));
        assertTrue(prompt.contains("文档上下文：\n文档片段 A"));
    }

    @Test
    void shouldFallbackToEmptyMemoryWithoutNullMarkers() {
        ConversationMemoryService.ConversationMemorySnapshot memory =
            new ConversationMemoryService.ConversationMemorySnapshot(
                null,
                List.of(),
                null,
                List.of(),
                null
            );

        String prompt = assembler.buildPrompt(memory, "文档片段 B", "这个问题怎么回答");

        assertTrue(prompt.contains("会话记忆：\n无"));
        assertFalse(prompt.contains("null"));
        assertFalse(prompt.contains("当前意图："));
        assertFalse(prompt.contains("已确认事实："));
        assertFalse(prompt.contains("历史摘要："));
        assertFalse(prompt.contains("最近对话："));
    }

    @Test
    void shouldRewriteFollowUpQuestionUsingIntentAnchor() {
        ConversationMemoryService.ConversationMemorySnapshot memory =
            new ConversationMemoryService.ConversationMemorySnapshot(
                "正在讨论文件上传限制",
                List.of("文件大小不能超过 50MB"),
                "定位上传失败原因",
                List.of(),
                Instant.now()
            );

        String rewritten = assembler.rewriteQuestion("继续说说", memory, 300, 500);
        assertEquals("定位上传失败原因：继续说说", rewritten);
    }
}
