package com.mark.knowledge.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的会话上下文服务。
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final int DEFAULT_FACT_LIMIT = 8;

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

    public ConversationMemorySnapshot getMemorySnapshot(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ConversationMemorySnapshot.empty();
        }

        ConversationSession session = sessions.get(conversationId);
        if (session == null) {
            return ConversationMemorySnapshot.empty();
        }

        session.touch();
        return session.snapshotMemory();
    }

    public void appendUserMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.USER, content);
    }

    public void appendAssistantMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.ASSISTANT, content);
    }

    public void updateSummary(String conversationId, String summary) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession());
        session.updateSummary(summary);
    }

    public void updateFacts(String conversationId, List<String> facts) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession());
        session.updateFacts(facts);
    }

    public void updateIntent(String conversationId, String intent) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession());
        session.updateIntent(intent);
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

    public record ConversationMemorySnapshot(
        String summary,
        List<String> facts,
        String intent,
        List<ConversationMessage> recentMessages,
        Instant lastAccessTime
    ) {
        private static ConversationMemorySnapshot empty() {
            return new ConversationMemorySnapshot(null, List.of(), null, List.of(), null);
        }
    }

    private static final class ConversationSession {
        private final List<ConversationMessage> recentMessages = new ArrayList<>();
        private String summary;
        private List<String> facts = List.of();
        private String intent;
        private Instant lastAccessTime = Instant.now();

        private synchronized void add(ConversationMessage message, int memoryWindow) {
            recentMessages.add(message);
            trimToWindow(memoryWindow);
            touch();
        }

        private synchronized List<ConversationMessage> snapshot() {
            return List.copyOf(recentMessages);
        }

        private synchronized ConversationMemorySnapshot snapshotMemory() {
            return new ConversationMemorySnapshot(
                summary,
                List.copyOf(facts),
                intent,
                List.copyOf(recentMessages),
                lastAccessTime
            );
        }

        private synchronized void updateSummary(String summary) {
            this.summary = normalizeOptional(summary);
            touch();
        }

        private synchronized void updateFacts(List<String> facts) {
            this.facts = normalizeFacts(facts);
            touch();
        }

        private synchronized void updateIntent(String intent) {
            this.intent = normalizeOptional(intent);
            touch();
        }

        private synchronized void trimToWindow(int memoryWindow) {
            int maxMessages = Math.max(memoryWindow, 1) * 2;
            while (recentMessages.size() > maxMessages) {
                recentMessages.remove(0);
            }
        }

        private synchronized void touch() {
            lastAccessTime = Instant.now();
        }

        private synchronized Instant lastAccessTime() {
            return lastAccessTime;
        }

        private static String normalizeOptional(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static List<String> normalizeFacts(List<String> facts) {
            if (facts == null || facts.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String fact : facts) {
                String value = normalizeOptional(fact);
                if (value != null) {
                    normalized.add(value);
                }
                if (normalized.size() >= DEFAULT_FACT_LIMIT) {
                    break;
                }
            }
            return List.copyOf(normalized);
        }
    }
}
