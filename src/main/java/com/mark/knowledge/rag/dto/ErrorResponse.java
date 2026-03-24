package com.mark.knowledge.rag.dto;

import java.time.LocalDateTime;

/**
 * 标准错误响应。
 */
public record ErrorResponse(
    String error,
    String message,
    LocalDateTime timestamp
) {
    public ErrorResponse(String error, String message) {
        this(error, message, LocalDateTime.now());
    }

    public ErrorResponse(String message) {
        this("错误", message, LocalDateTime.now());
    }
}
