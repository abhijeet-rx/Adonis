package com.adonis.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record WorkflowExecutionResult(
        String executionId,
        String workflowId,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        List<NodeExecutionResult> nodes,
        String error
) {
    public static WorkflowExecutionResult success(
            String executionId,
            String workflowId,
            Instant startedAt,
            Instant completedAt,
            List<NodeExecutionResult> nodes) {
        long duration = Duration.between(startedAt, completedAt).toMillis();
        return new WorkflowExecutionResult(
                executionId,
                workflowId,
                ExecutionStatus.SUCCESS,
                startedAt,
                completedAt,
                duration,
                nodes != null ? nodes : List.of(),
                null
        );
    }

    public static WorkflowExecutionResult failure(
            String executionId,
            String workflowId,
            Instant startedAt,
            Instant completedAt,
            List<NodeExecutionResult> nodes,
            String error) {
        long duration = Duration.between(startedAt, completedAt).toMillis();
        return new WorkflowExecutionResult(
                executionId,
                workflowId,
                ExecutionStatus.FAILED,
                startedAt,
                completedAt,
                duration,
                nodes != null ? nodes : List.of(),
                error != null ? error : "Workflow execution failed"
        );
    }
}
