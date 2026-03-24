package com.mark.knowledge.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的会话上下文服务。
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final int memoryWindow;
    private final long sessionTtlSeconds;
    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    public ConversationMemoryService(
            @Value("${rag.memory-window:6}") int memoryWindow,
            @Value("${rag.session-ttl-seconds:1800}") long sessionTtlSeconds) {
        this.memoryWindow = memoryWindow;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public List<ConversationMessage> getRecentMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }

        ConversationSession session = sessions.get(conversationId);
        if (session == null) {
            return List.of();
        }

        session.touch();
        return session.snapshot();
    }

    public void appendUserMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.USER, content);
    }

    public void appendAssistantMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.ASSISTANT, content);
    }

    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        sessions.remove(conversationId);
    }

    @Scheduled(fixedDelayString = "${rag.memory-cleanup-interval-ms:300000}")
    public void cleanupExpiredSessions() {
        Instant expireBefore = Instant.now().minusSeconds(sessionTtlSeconds);
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccessTime().isBefore(expireBefore));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("已清理 {} 个过期会话", removed);
        }
    }

    private void appendMessage(String conversationId, ConversationRole role, String content) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (content == null || content.isBlank()) {
            return;
        }

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession());
        session.add(new ConversationMessage(role, content.trim(), Instant.now()), memoryWindow);
    }

    public enum ConversationRole {
        USER,
        ASSISTANT
    }

    public record ConversationMessage(
        ConversationRole role,
        String content,
        Instant timestamp
    ) {
    }

    private static final class ConversationSession {
        private final List<ConversationMessage> messages = new ArrayList<>();
        private Instant lastAccessTime = Instant.now();

        private synchronized void add(ConversationMessage message, int memoryWindow) {
            messages.add(message);
            trimToWindow(memoryWindow);
            touch();
        }

        private synchronized List<ConversationMessage> snapshot() {
            return List.copyOf(messages);
        }

        private synchronized void trimToWindow(int memoryWindow) {
            int maxMessages = Math.max(memoryWindow, 1) * 2;
            while (messages.size() > maxMessages) {
                messages.remove(0);
            }
        }

        private synchronized void touch() {
            lastAccessTime = Instant.now();
        }

        private synchronized Instant lastAccessTime() {
            return lastAccessTime;
        }
    }
}
