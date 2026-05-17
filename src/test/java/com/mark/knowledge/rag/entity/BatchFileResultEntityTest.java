package com.mark.knowledge.rag.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class BatchFileResultEntityTest {

    @Test
    void shouldCreateValidEntity() {
        BatchFileResultEntity result = new BatchFileResultEntity();
        result.setId(1L);
        result.setTaskId("task-123");
        result.setFileName("test.pdf");
        result.setSuccess(true);
        result.setEmbeddingCount(5);
        result.setProcessedTime(LocalDateTime.now());

        assertEquals(1L, result.getId());
        assertEquals("task-123", result.getTaskId());
        assertEquals("test.pdf", result.getFileName());
        assertTrue(result.isSuccess());
        assertEquals(5, result.getEmbeddingCount());
        assertNotNull(result.getProcessedTime());
    }

    @Test
    void shouldHandleFailedResult() {
        BatchFileResultEntity result = new BatchFileResultEntity();
        result.setTaskId("task-456");
        result.setFileName("bad.pdf");
        result.setSuccess(false);
        result.setErrorMessage("File corrupted");
        result.setEmbeddingCount(0);
        result.setProcessedTime(LocalDateTime.now());

        assertFalse(result.isSuccess());
        assertEquals("File corrupted", result.getErrorMessage());
        assertEquals(0, result.getEmbeddingCount());
    }

    @Test
    void shouldHandleNullErrorMessage() {
        BatchFileResultEntity result = new BatchFileResultEntity();
        result.setTaskId("task-789");
        result.setFileName("good.pdf");
        result.setSuccess(true);

        assertNull(result.getErrorMessage());
        assertEquals(0, result.getEmbeddingCount());
        assertNull(result.getProcessedTime());
    }

    @Test
    void shouldSetAllFields() {
        LocalDateTime time = LocalDateTime.now();
        BatchFileResultEntity result = new BatchFileResultEntity();

        result.setId(99L);
        result.setTaskId("task-full");
        result.setFileName("document.docx");
        result.setSuccess(true);
        result.setErrorMessage(null);
        result.setEmbeddingCount(10);
        result.setProcessedTime(time);

        assertEquals(99L, result.getId());
        assertEquals("task-full", result.getTaskId());
        assertEquals("document.docx", result.getFileName());
        assertTrue(result.isSuccess());
        assertNull(result.getErrorMessage());
        assertEquals(10, result.getEmbeddingCount());
        assertEquals(time, result.getProcessedTime());
    }

    @Test
    void shouldHandleLongErrorMessage() {
        BatchFileResultEntity result = new BatchFileResultEntity();
        result.setTaskId("task-error");
        result.setFileName("error.pdf");
        result.setSuccess(false);
        result.setErrorMessage("A".repeat(400));
        result.setProcessedTime(LocalDateTime.now());

        assertEquals(400, result.getErrorMessage().length());
    }
}