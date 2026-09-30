package com.adonis.repository;

import com.adonis.execution.ExecutionStatus;
import com.adonis.model.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkflowExecutionRepository extends MongoRepository<WorkflowExecution, String> {

    Optional<WorkflowExecution> findByIdAndUserId(String id, String userId);

    Page<WorkflowExecution> findByWorkflowIdAndUserId(String workflowId, String userId, Pageable pageable);

    Page<WorkflowExecution> findAllByUserId(String userId, Pageable pageable);

    Page<WorkflowExecution> findByUserIdAndStatus(String userId, ExecutionStatus status, Pageable pageable);

    Optional<WorkflowExecution> findByWorkflowIdAndIdempotencyKey(String workflowId, String idempotencyKey);

    Optional<WorkflowExecution> findByScheduledOccurrence(String scheduledOccurrence);

    long countByWorkflowIdAndUserId(String workflowId, String userId);

    long countByUserId(String userId);

    long deleteByWorkflowIdAndUserId(String workflowId, String userId);

    long deleteByIdAndUserId(String id, String userId);
}
