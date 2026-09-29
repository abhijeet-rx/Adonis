package com.adonis.dto;

import com.adonis.execution.ExecutionStatus;

/**
 * Asynchronous response payload returned when an execution job is accepted and enqueued.
 */
public record ExecuteWorkflowResponse(
        String executionId,
        String workflowId,
        ExecutionStatus status
) {
    public static ExecuteWorkflowResponse queued(String executionId, String workflowId) {
        return new ExecuteWorkflowResponse(executionId, workflowId, ExecutionStatus.QUEUED);
    }
}
