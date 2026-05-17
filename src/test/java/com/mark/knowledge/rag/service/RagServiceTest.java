package com.mark.knowledge.rag.service;

import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private ConversationMemoryService memoryService;
    private RagRetrievalService retrievalService;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        memoryService = new ConversationMemoryService(3, 1800, 2000, 8, 200);
        RagContextAssembler assembler = new RagContextAssembler();
        retrievalService = new RagRetrievalService(embeddingModel, embeddingStore, new Bm25Scorer());
        RagMemoryOrchestrator memoryOrchestrator = new RagMemoryOrchestrator(chatModel, memoryService, assembler);
        ragService = new RagService(
            chatModel,
            streamingChatModel,
            memoryService,
            assembler,
            retrievalService,
            memoryOrchestrator,
            new RagStreamSessionManager()
        );
    }

    @Test
    void shouldUpdateIntentAndFactsDuringAskFlow() {
        Metadata metadata = Metadata.metadata("filename", "guide.txt");
        TextSegment segment = TextSegment.from("系统要求上传文件大小不能超过 50MB，且仅支持 pdf 文件。", metadata);
        Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f});
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.92, "m1", embedding, segment);

        when(chatModel.chat(any(String.class)))
            .thenReturn("定位上传限制")
            .thenReturn("请先检查文件大小与格式限制");
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(embedding));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
            .thenReturn(new EmbeddingSearchResult<>(List.of(match)));

        RagResponse response = ragService.ask(new RagRequest("为什么上传失败", "conv-1", 3));
        assertEquals("请先检查文件大小与格式限制", response.answer());

        ConversationMemoryService.ConversationMemorySnapshot snapshot = memoryService.getMemorySnapshot("conv-1");
        assertEquals("定位上传限制", snapshot.intent());
        assertEquals(List.of("系统要求上传文件大小不能超过 50MB，且仅支持 pdf 文件。"), snapshot.facts());
        assertEquals(2, snapshot.recentMessages().size());
    }

    @Test
    void shouldAllowMoreInitialRecallButRespectConfiguredMaxResultsWhenBm25Reranking() {
        ReflectionTestUtils.setField(retrievalService, "maxResults", 3);
        ReflectionTestUtils.setField(retrievalService, "rerankCandidateMultiplier", 4);

        Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f});
        List<EmbeddingMatch<TextSegment>> matches = List.of(
            buildMatch("m1", 0.91, "配置说明一：显存至少 16GB。", embedding),
            buildMatch("m2", 0.89, "配置说明二：内存建议 64GB。", embedding),
            buildMatch("m3", 0.87, "配置说明三：CPU 推荐 16 核。", embedding),
            buildMatch("m4", 0.85, "配置说明四：磁盘建议使用 SSD。", embedding),
            buildMatch("m5", 0.83, "配置说明五：网络带宽建议千兆。", embedding),
            buildMatch("m6", 0.81, "配置说明六：建议预留冗余资源。", embedding),
            buildMatch("m7", 0.79, "配置说明七：模型部署建议单独节点。", embedding),
            buildMatch("m8", 0.77, "配置说明八：建议开启监控告警。", embedding),
            buildMatch("m9", 0.75, "配置说明九：向量库建议独立存储。", embedding),
            buildMatch("m10", 0.73, "配置说明十：索引构建需要额外资源。", embedding),
            buildMatch("m11", 0.71, "配置说明十一：推理服务建议限流。", embedding),
            buildMatch("m12", 0.69, "配置说明十二：高并发场景需扩容。", embedding)
        );

        when(chatModel.chat(any(String.class)))
            .thenReturn("定位物理配置")
            .thenReturn("请优先参考物理配置相关片段");
        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(embedding));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
            .thenReturn(new EmbeddingSearchResult<>(matches));

        RagResponse response = ragService.ask(new RagRequest("搭建 RAG 需要什么物理配置", "conv-bm25", 10));

        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(captor.capture());
        assertEquals(12, captor.getValue().maxResults());
        assertEquals(3, response.sources().size());
    }

    private EmbeddingMatch<TextSegment> buildMatch(String id, double score, String text, Embedding embedding) {
        Metadata metadata = Metadata.metadata("filename", id + ".txt");
        TextSegment segment = TextSegment.from(text, metadata);
        return new EmbeddingMatch<>(score, id, embedding, segment);
    }
}
