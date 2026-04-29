package com.mark.knowledge.rag.repository;

import com.mark.knowledge.rag.entity.UploadedFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UploadedFileRepository extends JpaRepository<UploadedFileEntity, Long> {
    boolean existsByFileHash(String fileHash);

    boolean existsByOriginalFilename(String originalFilename);
}