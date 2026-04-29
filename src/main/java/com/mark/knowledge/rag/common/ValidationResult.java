package com.mark.knowledge.rag.common;

import lombok.Data;

@Data
public class ValidationResult {
    private boolean valid;
    private String message;

    public ValidationResult(boolean valid , String message) {
        this.valid = valid;
        this.message = message;
    }
}