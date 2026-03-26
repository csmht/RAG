package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import com.mark.knowledge.rag.dto.SourceReference;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 问答服务。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Value("${rag.max-results:5}")
    private int maxResults;

    @Value("${rag.min-score:0.5}")
    private double minScore;

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ConversationMemoryService conversationMemoryService;

    public RagService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            ConversationMemoryService conversationMemoryService) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.conversationMemoryService = conversationMemoryService;
    }

    public RagResponse ask(RagRequest request) {
        log.info("处理 RAG 问题: {}", request.question());

        try {
            String conversationId = request.conversationId();
            List<ConversationMemoryService.ConversationMessage> history = conversationMemoryService
                .getRecentMessages(conversationId);
            String rewrittenQuestion = rewriteQuestion(request.question(), history);
            var questionEmbedding = embeddingModel.embed(rewrittenQuestion).content();

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(request.maxResults() != null ? request.maxResults() : maxResults)
                .minScore(minScore)
                .build();

            log.info("问题向量维度: {}", questionEmbedding.dimension());

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

            log.info("检索到 {} 条相关片段，最小分数阈值: {}", matches.size(), minScore);

            if (matches.isEmpty()) {
                String emptyAnswer = "未在已上传文档中检索到足够相关的内容，请根据文档内容重新提问。";
                conversationMemoryService.appendUserMessage(conversationId, request.question());
                conversationMemoryService.appendAssistantMessage(conversationId, emptyAnswer);
                return new RagResponse(
                    emptyAnswer,
                    conversationId,
                    new ArrayList<>()
                );
            }

            String context = matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n---\n\n"));

            String prompt = buildPrompt(history, context, request.question());
            String answer = chatModel.chat(prompt);

            conversationMemoryService.appendUserMessage(conversationId, request.question());
            conversationMemoryService.appendAssistantMessage(conversationId, answer);

            List<SourceReference> sources = matches.stream()
                .map(match -> {
                    TextSegment segment = match.embedded();
                    String filename = segment.metadata() != null
                        ? segment.metadata().getString("filename")
                        : "unknown";
                    return new SourceReference(filename, segment.text(), match.score());
                })
                .collect(Collectors.toList());

            return new RagResponse(answer, conversationId, sources);
        } catch (Exception e) {
            log.error("RAG 处理失败", e);
            throw new RuntimeException("问题处理失败: " + e.getMessage(), e);
        }
    }

    private String rewriteQuestion(String question, List<ConversationMemoryService.ConversationMessage> history) {
        if (history.isEmpty()) {
            return question;
        }

        String historyText = formatHistory(history);
        String rewritePrompt = String.format("""
            你需要结合历史对话，把用户当前问题改写成一个完整、独立、可用于知识库检索的问题。
            如果当前问题本身已经完整，直接原样返回，不要增加解释。
            只输出改写后的问题，不要输出其它内容。

            历史对话：
            %s

            当前问题：
            %s
            """, historyText, question);

        String rewritten = chatModel.chat(rewritePrompt);
        return rewritten != null && !rewritten.isBlank() ? rewritten.trim() : question;
    }

    private String buildPrompt(
            List<ConversationMemoryService.ConversationMessage> history,
            String context,
            String question) {
        String historyText = history.isEmpty() ? "无" : formatHistory(history);

        return String.format("""
            你是一个基于文档内容回答问题的助手。
            你必须严格依据下面提供的历史对话和文档上下文回答。
            如果上下文中没有答案，请明确说明“根据已上传文档无法回答该问题”。
            不要编造，不要补充上下文之外的事实。

            历史对话：
            %s

            文档上下文：
            %s

            用户当前问题：%s

            请直接给出中文答案：""", historyText, context, question);
    }

    private String formatHistory(List<ConversationMemoryService.ConversationMessage> history) {
        return history.stream()
            .map(message -> (message.role() == ConversationMemoryService.ConversationRole.USER ? "用户：" : "助手：")
                + message.content())
            .collect(Collectors.joining("\n"));
    }
}
