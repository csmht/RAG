package com.mark.knowledge.rag.service;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RAG 文本处理辅助工具。
 */
final class RagTextSupport {

    static final List<String> INVALID_MARKERS = List.of("无", "未知", "未提及", "无法判断");
    static final String UNKNOWN_FILENAME = "unknown";
    static final String UNKNOWN_ERROR = "未知错误";

    private RagTextSupport() {
    }

    /**
     * 按最大长度截断文本，优先保留尾部最新内容。
     */
    static String trimToMaxLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        int safeMaxLength = Math.max(maxLength, 1);
        if (value.length() <= safeMaxLength) {
            return value;
        }
        return value.substring(value.length() - safeMaxLength);
    }
}
