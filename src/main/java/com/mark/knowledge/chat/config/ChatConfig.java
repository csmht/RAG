package com.mark.knowledge.chat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地 RAG 核心配置。
 */
@Configuration
public class ChatConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatConfig.class);

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.think:false}")
    private Boolean ollamaThink;

    @Value("${ollama.chat-model:qwen2.5:7b}")
    private String chatModelName;

    @Value("${ollama.embedding-model:qwen3-embedding:0.6b}")
    private String embeddingModelName;

    @Value("${ollama.timeout:120s}")
    private String ollamaTimeout;

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
        log.info("初始化本地聊天模型: {} @ {}", chatModelName, ollamaBaseUrl);
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(chatModelName)
                .think(ollamaThink)
                .temperature(0.7)
                .timeout(parseTimeout(ollamaTimeout))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("初始化本地嵌入模型: {} @ {}", embeddingModelName, ollamaBaseUrl);
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(embeddingModelName)
                .timeout(parseTimeout(ollamaTimeout))
                .build();
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

    private java.time.Duration parseTimeout(String timeout) {
        try {
            if (timeout.endsWith("s")) {
                long seconds = Long.parseLong(timeout.substring(0, timeout.length() - 1));
                return java.time.Duration.ofSeconds(seconds);
            }
            return java.time.Duration.ofSeconds(120);
        } catch (Exception e) {
            return java.time.Duration.ofSeconds(120);
        }
    }
}
