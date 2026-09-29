package com.adonis.dto;

import com.adonis.execution.ExecutionStatus;
import com.adonis.model.WorkflowExecution;
import com.adonis.util.SecretRedactor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ExecutionSummaryResponse(
        String id,
        String workflowId,
        ExecutionStatus status,
        String triggerType,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String error
) {
    @JsonProperty("executionId")
    public String executionId() {
        return id;
    }

    public static ExecutionSummaryResponse fromModel(WorkflowExecution model) {
        if (model == null) {
            return null;
        }
        return new ExecutionSummaryResponse(
                model.getId(),
                model.getWorkflowId(),
                model.getStatus(),
                model.getTriggerType(),
                model.getStartedAt(),
                model.getCompletedAt(),
                model.getDurationMs(),
                SecretRedactor.redactString(model.getError())
        );
    }
}
