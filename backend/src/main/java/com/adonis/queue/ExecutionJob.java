package com.adonis.queue;

import java.time.Instant;

/**
 * Lightweight, JSON-serializable job message for asynchronous workflow execution.
 * Contains only identifiers and execution metadata; authoritative workflow documents
 * are loaded directly from MongoDB by the worker.
 */
public record ExecutionJob(
        String executionId,
        String workflowId,
        String userId,
        String triggerType,
        Instant queuedAt
) {
    public ExecutionJob {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be null or blank");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be null or blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }
        if (triggerType == null || triggerType.isBlank()) {
            triggerType = "manual";
        }
        if (queuedAt == null) {
            queuedAt = Instant.now();
        }
    }
}
