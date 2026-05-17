package com.mark.knowledge.rag.service;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * RAG 流式会话管理服务。
 */
@Service
public class RagStreamSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RagStreamSessionManager.class);

    private final ConcurrentHashMap<String, InFlightGeneration> inFlightGenerations = new ConcurrentHashMap<>();

    /**
     * 解析流式场景下的会话标识。
     */
    public String resolveConversationIdForStream(String conversationId) {
        String normalized = normalizeConversationId(conversationId);
        if (StringUtils.hasText(normalized)) {
            return normalized;
        }
        return "rag-" + UUID.randomUUID();
    }

    /**
     * 解析通用会话标识。
     */
    public String normalizeConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        return conversationId.trim();
    }

    /**
     * 创建并注册一个新的流式会话。
     */
    public InFlightGeneration createGeneration(String conversationId, String question, long streamTimeoutMs) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        InFlightGeneration generation = new InFlightGeneration(
            UUID.randomUUID().toString(),
            conversationId,
            question,
            emitter
        );
        inFlightGenerations.put(conversationId, generation);
        return generation;
    }

    /**
     * 注册 SSE 生命周期回调。
     */
    public void bindEmitterLifecycle(InFlightGeneration generation) {
        generation.emitter().onCompletion(() -> {
            cleanupGeneration(generation);
            log.debug("SSE 连接完成: conversationId={}, requestId={}", generation.conversationId(), generation.requestId());
        });
        generation.emitter().onTimeout(() -> {
            log.warn("SSE 连接超时: conversationId={}, requestId={}", generation.conversationId(), generation.requestId());
            generation.markCancelled();
            generation.cancelHandle();
            sendEvent(generation, "cancelled", Map.of("conversationId", generation.conversationId(), "reason", "timeout"));
            completeGeneration(generation);
        });
        generation.emitter().onError(error -> {
            log.warn("SSE 连接错误: conversationId={}, requestId={}, error={}",
                generation.conversationId(), generation.requestId(), error == null ? "unknown" : error.getMessage());
            generation.markCancelled();
            generation.cancelHandle();
            completeGeneration(generation);
        });
    }

    /**
     * 异步执行流式后台任务。
     */
    public void runAsync(Runnable runnable) {
        CompletableFuture.runAsync(runnable);
    }

    /**
     * 取消指定会话的流式生成任务。
     */
    public boolean cancelGeneration(String conversationId, String reason) {
        InFlightGeneration generation = inFlightGenerations.get(conversationId);
        if (generation == null) {
            return false;
        }
        if (!generation.markCancelled()) {
            return false;
        }

        log.info("取消流式生成: conversationId={}, requestId={}, reason={}", conversationId, generation.requestId(), reason);
        generation.cancelHandle();
        sendEvent(generation, "cancelled", Map.of("conversationId", conversationId, "reason", reason));
        completeGeneration(generation);
        return true;
    }

    /**
     * 判断当前流式任务是否应该中止。
     */
    public boolean shouldAbort(InFlightGeneration generation) {
        return generation.isCancelled()
            || generation.isCompleted()
            || inFlightGenerations.get(generation.conversationId()) != generation;
    }

    /**
     * 发送 SSE 事件。
     */
    public void sendEvent(InFlightGeneration generation, String eventName, Object data) {
        if (generation.isCompleted()) {
            return;
        }
        try {
            generation.emitter().send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("发送 SSE 事件失败: conversationId={}, requestId={}, event={}",
                generation.conversationId(), generation.requestId(), eventName);
            generation.markCancelled();
            generation.cancelHandle();
            completeGeneration(generation);
        }
    }

    /**
     * 正常结束流式任务。
     */
    public void completeGeneration(InFlightGeneration generation) {
        if (!generation.markCompleted()) {
            return;
        }
        cleanupGeneration(generation);
        try {
            generation.emitter().complete();
        } catch (Exception e) {
            log.debug("结束 SSE 连接时忽略异常: conversationId={}, requestId={}, message={}",
                generation.conversationId(), generation.requestId(), e.getMessage());
        }
    }

    /**
     * 以异常方式结束流式任务。
     */
    public void completeWithError(InFlightGeneration generation, Throwable error) {
        if (!generation.markCompleted()) {
            return;
        }
        cleanupGeneration(generation);
        try {
            generation.emitter().completeWithError(error);
        } catch (Exception e) {
            log.debug("结束异常 SSE 连接时忽略异常: conversationId={}, requestId={}, message={}",
                generation.conversationId(), generation.requestId(), e.getMessage());
        }
    }

    /**
     * 返回统一的安全错误文案。
     */
    public String safeErrorMessage(Throwable error) {
        if (error == null || !StringUtils.hasText(error.getMessage())) {
            return "未知错误";
        }
        return error.getMessage();
    }

    /**
     * 创建标准流式响应处理器。
     */
    public StreamingChatResponseHandler createResponseHandler(
            InFlightGeneration generation,
            Consumer<String> onComplete,
            Consumer<Throwable> onError) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                handlePartialText(partialResponse);
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                if (context != null) {
                    generation.captureHandle(context.streamingHandle());
                }
                if (partialResponse != null) {
                    handlePartialText(partialResponse.text());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (generation.isCompleted()) {
                    return;
                }

                String finalAnswer = generation.answer();
                if (response != null && response.aiMessage() != null && StringUtils.hasText(response.aiMessage().text())) {
                    finalAnswer = response.aiMessage().text();
                }

                if (!generation.isCancelled()) {
                    if (StringUtils.hasText(finalAnswer)) {
                        onComplete.accept(finalAnswer);
                    }
                    sendEvent(generation, "complete", Map.of("conversationId", generation.conversationId(), "cancelled", false));
                } else {
                    sendEvent(generation, "complete", Map.of("conversationId", generation.conversationId(), "cancelled", true));
                }
                completeGeneration(generation);
            }

            @Override
            public void onError(Throwable error) {
                if (generation.isCancelled()) {
                    sendEvent(generation, "complete", Map.of("conversationId", generation.conversationId(), "cancelled", true));
                    completeGeneration(generation);
                    return;
                }

                log.error("流式模型响应失败: conversationId={}, requestId={}", generation.conversationId(), generation.requestId(), error);
                sendEvent(generation, "error", Map.of("message", safeErrorMessage(error)));
                onError.accept(error);
            }

            /**
             * 处理模型增量文本并转发到 SSE。
             */
            private void handlePartialText(String text) {
                if (!StringUtils.hasText(text) || generation.isCancelled() || generation.isCompleted()) {
                    return;
                }
                generation.appendAnswer(text);
                sendEvent(generation, "delta", text);
            }
        };
    }

    /**
     * 清理指定会话的注册状态。
     */
    private void cleanupGeneration(InFlightGeneration generation) {
        inFlightGenerations.compute(generation.conversationId(), (conversationId, current) -> {
            if (current == null) {
                return null;
            }
            return generation.requestId().equals(current.requestId()) ? null : current;
        });
    }

    /**
     * 流式会话状态对象。
     */
    public static final class InFlightGeneration {
        private final String requestId;
        private final String conversationId;
        private final String question;
        private final SseEmitter emitter;
        private final AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final StringBuilder answerBuilder = new StringBuilder();

        /**
         * 创建流式会话状态对象。
         */
        public InFlightGeneration(String requestId, String conversationId, String question, SseEmitter emitter) {
            this.requestId = requestId;
            this.conversationId = conversationId;
            this.question = question;
            this.emitter = emitter;
        }

        /**
         * 返回请求标识。
         */
        public String requestId() {
            return requestId;
        }

        /**
         * 返回会话标识。
         */
        public String conversationId() {
            return conversationId;
        }

        /**
         * 返回原始问题。
         */
        public String question() {
            return question;
        }

        /**
         * 返回 SSE 发射器。
         */
        public SseEmitter emitter() {
            return emitter;
        }

        /**
         * 捕获模型流式句柄。
         */
        public void captureHandle(StreamingHandle handle) {
            if (handle == null) {
                return;
            }

            StreamingHandle previous = handleRef.getAndSet(handle);
            if (previous != null && previous != handle && !previous.isCancelled()) {
                previous.cancel();
            }

            if (isCancelled() || isCompleted()) {
                handle.cancel();
            }
        }

        /**
         * 取消当前保存的流式句柄。
         */
        public void cancelHandle() {
            StreamingHandle handle = handleRef.get();
            if (handle == null) {
                return;
            }
            try {
                handle.cancel();
            } catch (Exception ignored) {
            }
        }

        /**
         * 标记当前任务已取消。
         */
        public boolean markCancelled() {
            return cancelled.compareAndSet(false, true);
        }

        /**
         * 判断当前任务是否已取消。
         */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * 标记当前任务已完成。
         */
        public boolean markCompleted() {
            return completed.compareAndSet(false, true);
        }

        /**
         * 判断当前任务是否已完成。
         */
        public boolean isCompleted() {
            return completed.get();
        }

        /**
         * 追加模型返回的增量答案文本。
         */
        public void appendAnswer(String text) {
            synchronized (answerBuilder) {
                answerBuilder.append(text);
            }
        }

        /**
         * 返回已累积的答案文本。
         */
        public String answer() {
            synchronized (answerBuilder) {
                return answerBuilder.toString();
            }
        }
    }
}
