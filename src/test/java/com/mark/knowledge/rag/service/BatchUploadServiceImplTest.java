package com.mark.knowledge.rag.service;

import com.mark.knowledge.auth.common.Result;
import com.mark.knowledge.rag.dto.BatchUploadDTO;
import com.mark.knowledge.rag.dto.BatchUploadTaskStatusDTO;
import com.mark.knowledge.rag.dto.ProcessedFileDTO;
import com.mark.knowledge.rag.entity.BatchTaskEntity;
import com.mark.knowledge.rag.repository.BatchTaskRepository;
import com.mark.knowledge.rag.repository.BatchFileResultRepository;
import com.mark.knowledge.rag.repository.UploadedFileRepository;
import com.mark.knowledge.rag.service.Impl.BatchUploadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BatchUploadServiceImplTest {

    @Mock
    private MultiFormatDocumentService multiFormatService;
    @Mock
    private BatchTaskRepository batchTaskRepository;
    @Mock
    private UploadedFileRepository uploadedFileRepository;
    @Mock
    private BatchFileResultRepository batchFileResultRepository;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    private BatchUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BatchUploadServiceImpl(
                multiFormatService,
                batchTaskRepository,
                uploadedFileRepository,
                batchFileResultRepository
        );
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void shouldAcceptEmptyFileList() {
        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldRejectFileCountExceeding50() {
        List<MultipartFile> files = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> (MultipartFile) new MockMultipartFile("file", "test.pdf",
                        "application/pdf", new byte[]{1}))
                .toList();
        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(files);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));
        assertTrue(ex.getMessage().contains("超过限制"));
    }

    @Test
    void shouldRejectUnsupportedFileType() {
        MockMultipartFile file = new MockMultipartFile("file", "test.exe",
                "application/octet-stream", new byte[]{1, 2, 3});
        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(List.of(file));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));
        assertTrue(ex.getMessage().contains("不支持的文件类型"));
    }

    @Test
    void shouldRejectEmptyFilename() {
        MockMultipartFile file = new MockMultipartFile("file", "",
                "application/pdf", new byte[]{1, 2, 3});
        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(List.of(file));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));
        assertTrue(ex.getMessage().contains("文件名不能为空"));
    }

    @Test
    void shouldRejectOversizedFile() {
        byte[] largeContent = new byte[51 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "large.pdf",
                "application/pdf", largeContent);
        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(List.of(file));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));
        assertTrue(ex.getMessage().contains("超过限制"));
    }

    @Test
    void shouldRejectInvalidPdfHeader() {
        byte[] invalidPdf = "NOT A PDF FILE".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf",
                "application/pdf", invalidPdf);
        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(List.of(file));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));
        assertTrue(ex.getMessage().contains("PDF文件格式无效"));
    }

    @Test
    void shouldCreateTaskWithPdfFile() {
        byte[] validPdf = "%PDF-1.4 fake pdf content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "valid.pdf",
                "application/pdf", validPdf);

        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.isAuthenticated()).thenReturn(true);
        lenient().when(authentication.getName()).thenReturn("testuser");
        lenient().when(batchTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessedFileDTO mockResult = new ProcessedFileDTO();
        mockResult.setSuccess(true);
        mockResult.setOriginalFilename("valid.pdf");
        lenient().when(multiFormatService.processMultiFormatFile(any(), anyString()))
                .thenReturn(Result.success(mockResult));

        BatchUploadDTO request = new BatchUploadDTO();
        request.setFiles(List.of(file));
        request.setUserId("testuser");

        String taskId = service.create(request);
        assertNotNull(taskId);
    }

    @Test
    void shouldThrowWhenTaskNotFound() {
        when(batchTaskRepository.findByTaskId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.getTaskStatus("nonexistent"));
    }

    @Test
    void shouldRejectUnauthorizedUserAccessingTask() {
        BatchTaskEntity task = new BatchTaskEntity();
        task.setTaskId("task-123");
        task.setUserId("owner");

        when(batchTaskRepository.findByTaskId("task-123")).thenReturn(Optional.of(task));
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("otheruser");
        when(authentication.getAuthorities()).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.getTaskStatus("task-123"));
    }

    @Test
    void shouldAllowOwnerToAccessTaskStatus() {
        BatchTaskEntity task = new BatchTaskEntity();
        task.setTaskId("task-123");
        task.setUserId("testuser");
        task.setStatus(BatchTaskEntity.BatchTaskStatus.PROCESSING);
        task.setTotalFiles(5);
        task.setSuccessCount(3);
        task.setFailureCount(1);

        when(batchTaskRepository.findByTaskId("task-123")).thenReturn(Optional.of(task));
        when(batchFileResultRepository.findByTaskId("task-123")).thenReturn(List.of());
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("testuser");
        lenient().when(authentication.getAuthorities()).thenReturn(List.of());

        BatchUploadTaskStatusDTO status = service.getTaskStatus("task-123");
        assertEquals("task-123", status.getTaskId());
        assertEquals("PROCESSING", status.getStatus());
        assertEquals(5, status.getTotalFiles());
    }

    @Test
    void shouldAllowAdminToAccessAnyTask() {
        BatchTaskEntity task = new BatchTaskEntity();
        task.setTaskId("task-456");
        task.setUserId("someone-else");
        task.setStatus(BatchTaskEntity.BatchTaskStatus.COMPLETED);
        task.setTotalFiles(10);

        when(batchTaskRepository.findByTaskId("task-456")).thenReturn(Optional.of(task));
        when(batchFileResultRepository.findByTaskId("task-456")).thenReturn(List.of());

        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");

        GrantedAuthority adminAuth = () -> "ROLE_ADMIN";
        when(authentication.getAuthorities()).thenReturn((Collection) List.of(adminAuth));

        BatchUploadTaskStatusDTO status = service.getTaskStatus("task-456");
        assertNotNull(status);
        assertEquals("COMPLETED", status.getStatus());
    }
}