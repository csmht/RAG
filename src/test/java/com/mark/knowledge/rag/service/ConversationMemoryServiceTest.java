package com.mark.knowledge.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryServiceTest {

    @Test
    void shouldKeepRecentMessagesCompatibleWithOldInterface() {
        ConversationMemoryService service = new ConversationMemoryService(2, 1800);

        service.appendUserMessage("c1", "问题 1");
        service.appendAssistantMessage("c1", "回答 1");
        service.appendUserMessage("c1", "问题 2");
        service.appendAssistantMessage("c1", "回答 2");
        service.appendUserMessage("c1", "问题 3");
        service.appendAssistantMessage("c1", "回答 3");

        List<ConversationMemoryService.ConversationMessage> messages = service.getRecentMessages("c1");
        assertEquals(4, messages.size());
        assertEquals("问题 2", messages.get(0).content());
        assertEquals("回答 3", messages.get(3).content());
    }

    @Test
    void shouldExposeStructuredSnapshotWithoutBreakingDefaults() {
        ConversationMemoryService service = new ConversationMemoryService(3, 1800);

        service.appendUserMessage("c2", "请帮我分析上传失败");
        service.updateSummary("c2", "用户正在排查上传失败问题");
        service.updateFacts("c2", List.of("当前接口是 /api/rag/upload", "当前接口是 /api/rag/upload", "  ", "文件类型是 pdf"));
        service.updateIntent("c2", "定位上传失败原因");

        ConversationMemoryService.ConversationMemorySnapshot snapshot = service.getMemorySnapshot("c2");
        assertEquals("用户正在排查上传失败问题", snapshot.summary());
        assertEquals(List.of("当前接口是 /api/rag/upload", "文件类型是 pdf"), snapshot.facts());
        assertEquals("定位上传失败原因", snapshot.intent());
        assertEquals(1, snapshot.recentMessages().size());
        assertNotNull(snapshot.lastAccessTime());
    }

    @Test
    void shouldReturnEmptySnapshotForBlankConversationId() {
        ConversationMemoryService service = new ConversationMemoryService(3, 1800);

        ConversationMemoryService.ConversationMemorySnapshot snapshot = service.getMemorySnapshot(" ");
        assertNull(snapshot.summary());
        assertTrue(snapshot.facts().isEmpty());
        assertNull(snapshot.intent());
        assertTrue(snapshot.recentMessages().isEmpty());
        assertNull(snapshot.lastAccessTime());
    }

    @Test
    void shouldIgnoreBlankConversationAndContent() {
        ConversationMemoryService service = new ConversationMemoryService(3, 1800);

        service.appendUserMessage(" ", "问题");
        service.appendAssistantMessage("c3", "   ");
        service.updateSummary(" ", "摘要");
        service.updateIntent("c3", "  ");
        service.updateFacts("c3", java.util.Arrays.asList("  ", null));

        assertTrue(service.getRecentMessages("c3").isEmpty());
        ConversationMemoryService.ConversationMemorySnapshot snapshot = service.getMemorySnapshot("c3");
        assertNull(snapshot.summary());
        assertTrue(snapshot.facts().isEmpty());
        assertNull(snapshot.intent());
    }

    @Test
    void shouldCleanupExpiredSessions() throws InterruptedException {
        ConversationMemoryService service = new ConversationMemoryService(3, 0);

        service.appendUserMessage("expired", "过期问题");
        Thread.sleep(5L);
        service.cleanupExpiredSessions();

        assertTrue(service.getRecentMessages("expired").isEmpty());
    }
}
