package com.mark.knowledge.chat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

/**
 * 本地 RAG 核心配置。
 */
@Configuration
public class ChatConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatConfig.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    @Value("${llm.provider:ollama}")
    private String llmProvider;

    @Value("${llm.timeout:120s}")
    private String llmTimeout;

    @Value("${llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${llm.ollama.chat-model:qwen2.5:7b}")
    private String ollamaChatModelName;

    @Value("${llm.ollama.embedding-model:bge-base-zh}")
    private String ollamaEmbeddingModelName;

    @Value("${llm.ollama.think:${ollama.think:false}}")
    private Boolean ollamaThink;

    @Value("${llm.chat-model:${ollama.chat-model:}}")
    private String chatModelName;

    @Value("${llm.embedding-model:${ollama.embedding-model:}}")
    private String embeddingModelName;

    @Value("${llm.vllm.base-url:http://localhost:8000/v1}")
    private String vllmBaseUrl;

    @Value("${llm.vllm.chat-model:Qwen/Qwen2.5-7B-Instruct}")
    private String vllmChatModelName;

    @Value("${llm.vllm.embedding-model:BAAI/bge-base-zh-v1.5}")
    private String vllmEmbeddingModelName;

    @Value("${llm.vllm.api-key:}")
    private String vllmApiKey;

    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.port:6334}")
    private int qdrantPort;

    @Value("${qdrant.collection-name:knowledge-base}")
    private String collectionName;

    @Value("${qdrant.vector-size:1024}")
    private int vectorSize;

    @Bean
    public ChatModel chatModel() {
        Duration timeout = parseTimeout(llmTimeout);
        return switch (normalizedProvider()) {
            case "ollama" -> createOllamaChatModel(timeout);
            case "vllm" -> createVllmChatModel(timeout);
            default -> throw unsupportedProvider();
        };
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        Duration timeout = parseTimeout(llmTimeout);
        return switch (normalizedProvider()) {
            case "ollama" -> createOllamaEmbeddingModel(timeout);
            case "vllm" -> createVllmEmbeddingModel(timeout);
            default -> throw unsupportedProvider();
        };
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("初始化 Qdrant 向量存储: {}:{} / {} / {}", qdrantHost, qdrantPort, collectionName, vectorSize);
        return QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantPort)
                .collectionName(collectionName)
                .build();
    }

    private ChatModel createOllamaChatModel(Duration timeout) {
        String resolvedModelName = resolveOllamaChatModelName();
        log.info("初始化聊天模型: provider=ollama, baseUrl={}, model={}, think={}", ollamaBaseUrl, resolvedModelName, ollamaThink);
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(resolvedModelName)
                .temperature(0.7)
                .think(Boolean.TRUE.equals(ollamaThink))
                .timeout(timeout)
                .build();
    }

    private EmbeddingModel createOllamaEmbeddingModel(Duration timeout) {
        String resolvedModelName = resolveOllamaEmbeddingModelName();
        log.info("初始化嵌入模型: provider=ollama, baseUrl={}, model={}", ollamaBaseUrl, resolvedModelName);
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(resolvedModelName)
                .timeout(timeout)
                .build();
    }

    private ChatModel createVllmChatModel(Duration timeout) {
        String resolvedModelName = resolveVllmChatModelName();
        log.info("初始化聊天模型: provider=vllm, baseUrl={}, model={}", vllmBaseUrl, resolvedModelName);
        var builder = OpenAiChatModel.builder()
                .baseUrl(vllmBaseUrl)
                .modelName(resolvedModelName)
                .temperature(0.7)
                .timeout(timeout);
        if (StringUtils.hasText(vllmApiKey)) {
            builder.apiKey(vllmApiKey);
        }
        return builder.build();
    }

    private EmbeddingModel createVllmEmbeddingModel(Duration timeout) {
        String resolvedModelName = resolveVllmEmbeddingModelName();
        log.info("初始化嵌入模型: provider=vllm, baseUrl={}, model={}", vllmBaseUrl, resolvedModelName);
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(vllmBaseUrl)
                .modelName(resolvedModelName)
                .timeout(timeout);
        if (StringUtils.hasText(vllmApiKey)) {
            builder.apiKey(vllmApiKey);
        }
        return builder.build();
    }

    private String resolveOllamaChatModelName() {
        if (StringUtils.hasText(chatModelName)) {
            return chatModelName;
        }
        return ollamaChatModelName;
    }

    private String resolveOllamaEmbeddingModelName() {
        if (StringUtils.hasText(embeddingModelName)) {
            return embeddingModelName;
        }
        return ollamaEmbeddingModelName;
    }

    private String resolveVllmChatModelName() {
        if (StringUtils.hasText(chatModelName)) {
            return chatModelName;
        }
        return vllmChatModelName;
    }

    private String resolveVllmEmbeddingModelName() {
        if (StringUtils.hasText(embeddingModelName)) {
            return embeddingModelName;
        }
        return vllmEmbeddingModelName;
    }

    private String normalizedProvider() {
        String provider = llmProvider == null ? "" : llmProvider.trim().toLowerCase(Locale.ROOT);
        if ("ollama".equals(provider) || "vllm".equals(provider)) {
            return provider;
        }
        throw unsupportedProvider();
    }

    private IllegalArgumentException unsupportedProvider() {
        return new IllegalArgumentException("不支持的 llm.provider=" + llmProvider + "，仅支持 ollama 或 vllm");
    }

    private Duration parseTimeout(String timeout) {
        try {
            if (!StringUtils.hasText(timeout)) {
                return DEFAULT_TIMEOUT;
            }
            String value = timeout.trim().toLowerCase(Locale.ROOT);
            if (value.endsWith("ms")) {
                long milliseconds = Long.parseLong(value.substring(0, value.length() - 2));
                return Duration.ofMillis(milliseconds);
            }
            if (value.endsWith("s")) {
                long seconds = Long.parseLong(value.substring(0, value.length() - 1));
                return Duration.ofSeconds(seconds);
            }
            if (value.endsWith("m")) {
                long minutes = Long.parseLong(value.substring(0, value.length() - 1));
                return Duration.ofMinutes(minutes);
            }
            return Duration.parse(timeout);
        } catch (Exception e) {
            log.warn("解析 llm.timeout 失败，使用默认值 {}: {}", DEFAULT_TIMEOUT, timeout);
            return DEFAULT_TIMEOUT;
        }
    }
}
