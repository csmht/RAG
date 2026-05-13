package com.mark.knowledge.rag.repository;

import com.mark.knowledge.rag.entity.BatchFileResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批量上传文件处理结果仓库
 *
 * @author mark
 */
@Repository
public interface BatchFileResultRepository extends JpaRepository<BatchFileResultEntity, Long> {
    List<BatchFileResultEntity> findByTaskId(String taskId);
}