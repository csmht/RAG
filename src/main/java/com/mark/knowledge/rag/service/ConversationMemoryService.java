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
    private static final int SUMMARY_BATCH_SIZE = 2;

    private final int memoryWindow;
    private final long sessionTtlSeconds;
    private final int summaryMaxLength;
    private final int factsMaxCount;
    private final int intentMaxLength;
    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    /**
     * 使用配置参数初始化会话记忆服务。
     */
    public ConversationMemoryService(
            @Value("${rag.memory-window:6}") int memoryWindow,
            @Value("${rag.session-ttl-seconds:1800}") long sessionTtlSeconds,
            @Value("${rag.summary-max-length:2000}") int summaryMaxLength,
            @Value("${rag.facts-max-count:8}") int factsMaxCount,
            @Value("${rag.intent-max-length:200}") int intentMaxLength) {
        this.memoryWindow = memoryWindow;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.summaryMaxLength = Math.max(summaryMaxLength, 1);
        this.factsMaxCount = Math.max(factsMaxCount, 1);
        this.intentMaxLength = Math.max(intentMaxLength, 1);
    }

    /**
     * 获取指定会话的最近原始消息列表。
     */
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

    /**
     * 获取指定会话的结构化记忆快照。
     */
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

    /**
     * 追加一条用户消息到指定会话。
     */
    public void appendUserMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.USER, content);
    }

    /**
     * 追加一条助手消息到指定会话。
     */
    public void appendAssistantMessage(String conversationId, String content) {
        appendMessage(conversationId, ConversationRole.ASSISTANT, content);
    }

    /**
     * 更新指定会话的历史摘要。
     */
    public void updateSummary(String conversationId, String summary) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession(summaryMaxLength, factsMaxCount, intentMaxLength));
        session.updateSummary(summary);
    }

    /**
     * 更新指定会话的稳定事实列表。
     */
    public void updateFacts(String conversationId, List<String> facts) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession(summaryMaxLength, factsMaxCount, intentMaxLength));
        session.updateFacts(facts);
    }

    /**
     * 更新指定会话的当前意图。
     */
    public void updateIntent(String conversationId, String intent) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession(summaryMaxLength, factsMaxCount, intentMaxLength));
        session.updateIntent(intent);
    }

    /**
     * 判断指定会话的最近消息是否已达到压缩阈值。
     */
    public boolean shouldCompressSummary(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        ConversationSession session = sessions.get(conversationId);
        return session != null && session.shouldCompress(memoryWindow);
    }

    /**
     * 获取用于摘要压缩的完整会话文本视图。
     */
    public String getSummarySource(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "";
        }
        ConversationSession session = sessions.get(conversationId);
        if (session == null) {
            return "";
        }
        session.touch();
        return session.summarySource();
    }

    /**
     * 清空指定会话的全部记忆内容。
     */
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        sessions.remove(conversationId);
    }

    /**
     * 清理超过存活时间的会话记忆。
     */
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

        ConversationSession session = sessions.computeIfAbsent(conversationId, ignored -> new ConversationSession(summaryMaxLength, factsMaxCount, intentMaxLength));
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
        private final int summaryMaxLength;
        private final int factsMaxCount;
        private final int intentMaxLength;
        private final List<ConversationMessage> recentMessages = new ArrayList<>();
        private String summary;
        private List<String> facts = List.of();
        private String intent;
        private Instant lastAccessTime = Instant.now();

        private ConversationSession(int summaryMaxLength, int factsMaxCount, int intentMaxLength) {
            this.summaryMaxLength = Math.max(summaryMaxLength, 1);
            this.factsMaxCount = Math.max(factsMaxCount, 1);
            this.intentMaxLength = Math.max(intentMaxLength, 1);
        }

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
            this.summary = normalizeSummary(summary);
            touch();
        }

        private synchronized void updateFacts(List<String> facts) {
            this.facts = normalizeFacts(facts, factsMaxCount);
            touch();
        }

        private synchronized void updateIntent(String intent) {
            this.intent = normalizeIntent(intent);
            touch();
        }

        /**
         * 判断当前最近消息是否已达到摘要压缩阈值。
         */
        private synchronized boolean shouldCompress(int memoryWindow) {
            int maxMessages = Math.max(memoryWindow, 1) * 2;
            return recentMessages.size() >= maxMessages;
        }

        /**
         * 生成用于摘要压缩的完整会话文本视图。
         */
        private synchronized String summarySource() {
            List<String> sections = new ArrayList<>();
            if (summary != null && !summary.isBlank()) {
                sections.add("历史摘要：\n" + summary);
            }
            if (!recentMessages.isEmpty()) {
                String recentText = recentMessages.stream()
                    .map(message -> (message.role() == ConversationRole.USER ? "用户：" : "助手：") + message.content())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
                sections.add("最近对话：\n" + recentText);
            }
            return String.join("\n\n", sections);
        }

        /**
         * 按窗口大小裁剪最近消息，并将溢出消息沉淀到摘要中。
         */
        private synchronized void trimToWindow(int memoryWindow) {
            int maxMessages = Math.max(memoryWindow, 1) * 2;
            while (recentMessages.size() > maxMessages) {
                int removeCount = Math.min(SUMMARY_BATCH_SIZE, recentMessages.size() - maxMessages);
                if (removeCount <= 0) {
                    break;
                }
                summarizeAndRemove(removeCount);
            }
        }

        private synchronized void summarizeAndRemove(int removeCount) {
            List<ConversationMessage> removedMessages = new ArrayList<>(removeCount);
            for (int i = 0; i < removeCount; i++) {
                removedMessages.add(recentMessages.remove(0));
            }
            appendSummary(removedMessages);
        }

        private void appendSummary(List<ConversationMessage> removedMessages) {
            if (removedMessages.isEmpty()) {
                return;
            }
            String removedSummary = removedMessages.stream()
                .map(message -> (message.role() == ConversationRole.USER ? "用户：" : "助手：") + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
            if (removedSummary == null) {
                return;
            }
            if (summary == null) {
                summary = trimToMaxLength(removedSummary, summaryMaxLength);
                return;
            }
            summary = trimToMaxLength(summary + "\n" + removedSummary, summaryMaxLength);
        }

        private synchronized void touch() {
            lastAccessTime = Instant.now();
        }

        private synchronized Instant lastAccessTime() {
            return lastAccessTime;
        }

        private String normalizeSummary(String summary) {
            String normalized = normalizeOptional(summary);
            if (normalized == null) {
                return null;
            }
            return trimToMaxLength(normalized, summaryMaxLength);
        }

        private String normalizeIntent(String intent) {
            String normalized = normalizeOptional(intent);
            if (normalized == null) {
                return null;
            }
            return trimToMaxLength(normalized, intentMaxLength);
        }

        private static String normalizeOptional(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static List<String> normalizeFacts(List<String> facts, int maxFactsCount) {
            if (facts == null || facts.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String fact : facts) {
                String value = normalizeOptional(fact);
                if (value != null) {
                    normalized.add(value);
                }
                if (normalized.size() >= Math.max(maxFactsCount, 1)) {
                    break;
                }
            }
            return List.copyOf(normalized);
        }

        private static String trimToMaxLength(String value, int maxLength) {
            if (value == null) {
                return null;
            }
            int safeMaxLength = Math.max(maxLength, 1);
            if (value.length() <= safeMaxLength) {
                return value;
            }
            return value.substring(value.length() - safeMaxLength);
        }
    }
}
