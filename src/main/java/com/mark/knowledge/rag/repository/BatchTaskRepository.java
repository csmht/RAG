package com.mark.knowledge.rag.repository;

import com.mark.knowledge.rag.entity.BatchTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BatchTaskRepository extends JpaRepository<BatchTaskEntity, Long> {

    /**
     * 根据任务ID查找任务
     */
    Optional<BatchTaskEntity> findByTaskId(String taskId);

}