package com.mark.knowledge.rag.app;

import com.mark.knowledge.rag.dto.ErrorResponse;
import com.mark.knowledge.rag.dto.RagRequest;
import com.mark.knowledge.rag.dto.RagResponse;
import com.mark.knowledge.rag.service.ConversationMemoryService;
import com.mark.knowledge.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG（检索增强生成）查询控制器
 *
 * @author mark
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;
    private final ConversationMemoryService conversationMemoryService;

    public RagController(RagService ragService, ConversationMemoryService conversationMemoryService) {
        this.ragService = ragService;
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * 使用 RAG 回答问题
     *
     * @param request RAG 请求（包含问题）
     * @return 答案及来源信息
     */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody RagRequest request) {
        log.info("收到 RAG 问题: {}", request.question());

        try {
            if (request.question() == null || request.question().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("无效请求", "问题不能为空"));
            }

            RagResponse response = ragService.ask(request);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("RAG 请求失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("请求失败", e.getMessage()));
        }
    }

    /**
     * 使用 RAG 流式回答问题（SSE）
     *
     * @param request RAG 请求（包含问题）
     * @return SSE 流
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> askStream(@RequestBody RagRequest request) {
        log.info("收到流式 RAG 问题: {}", request.question());

        try {
            if (request.question() == null || request.question().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("无效请求", "问题不能为空"));
            }

            SseEmitter emitter = ragService.askStream(request);
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);

        } catch (Exception e) {
            log.error("流式 RAG 请求失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("请求失败", e.getMessage()));
        }
    }

    /**
     * 取消指定会话的进行中生成任务
     *
     * @param conversationId 会话 ID
     * @return 取消结果
     */
    @PostMapping("/conversations/{conversationId}/cancel")
    public ResponseEntity<?> cancelConversationGeneration(@PathVariable String conversationId) {
        boolean cancelled = ragService.cancelGeneration(conversationId);
        if (cancelled) {
            return ResponseEntity.ok("已取消该会话的进行中生成任务");
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("未找到任务", "该会话当前没有进行中的生成任务"));
    }

    /**
     * 清空指定会话上下文
     *
     * @param conversationId 会话ID
     * @return 清理结果
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<String> clearConversation(@PathVariable String conversationId) {
        ragService.cancelGeneration(conversationId);
        conversationMemoryService.clear(conversationId);
        return ResponseEntity.ok("会话上下文已清空");
    }

    /**
     * 健康检查接口
     *
     * @return 健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("RAG 服务运行正常");
    }
}
