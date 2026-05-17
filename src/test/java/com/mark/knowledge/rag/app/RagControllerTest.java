package com.mark.knowledge.rag.app;

import com.mark.knowledge.rag.dto.ErrorResponse;
import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import com.mark.knowledge.rag.service.ConversationMemoryService;
import com.mark.knowledge.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagControllerTest {

    @Mock
    private RagService ragService;

    @Mock
    private ConversationMemoryService conversationMemoryService;

    private RagController controller;

    @BeforeEach
    void setUp() {
        controller = new RagController();
        ReflectionTestUtils.setField(controller, "ragService", ragService);
        ReflectionTestUtils.setField(controller, "conversationMemoryService", conversationMemoryService);
    }

    @Test
    void shouldReturnOkForAsk() {
        when(ragService.ask(any(RagRequest.class))).thenReturn(new RagResponse("答案", "conv-1", List.of()));

        ResponseEntity<?> response = controller.ask(new RagRequest("问题", "conv-1", 3));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(RagResponse.class, response.getBody());
    }

    @Test
    void shouldReturnSseForAskStream() {
        when(ragService.askStream(any(RagRequest.class))).thenReturn(new SseEmitter());

        ResponseEntity<?> response = controller.askStream(new RagRequest("问题", "conv-1", 3));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(SseEmitter.class, response.getBody());
    }

    @Test
    void shouldCancelAndClearConversation() {
        when(ragService.cancelGeneration("conv-1")).thenReturn(true);

        ResponseEntity<String> response = controller.clearConversation("conv-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(ragService).cancelGeneration("conv-1");
        verify(conversationMemoryService).clear("conv-1");
    }
}
