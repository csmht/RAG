package com.mark.knowledge.rag.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagStreamSessionManagerTest {

    private final RagStreamSessionManager manager = new RagStreamSessionManager();

    @Test
    void shouldCreateAndCancelGeneration() {
        String conversationId = "conv-" + UUID.randomUUID();
        RagStreamSessionManager.InFlightGeneration generation = manager.createGeneration(conversationId, "问题", 1000L);

        assertNotNull(generation.emitter());
        assertTrue(manager.cancelGeneration(conversationId, "测试取消"));
        assertFalse(manager.cancelGeneration(conversationId, "再次取消"));
    }

    @Test
    void shouldResolveConversationIdForStream() {
        String generated = manager.resolveConversationIdForStream(" ");
        assertTrue(generated.startsWith("rag-"));
        assertEquals("conv-1", manager.normalizeConversationId(" conv-1 "));
    }

    private void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
