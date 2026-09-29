package com.adonis.dto;

import com.adonis.execution.ExecutionStatus;
import com.adonis.model.WorkflowExecution;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public record ExecutionResponse(
        String id,
        String workflowId,
        ExecutionStatus status,
        String triggerType,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        List<NodeExecutionResponse> nodeExecutions,
        String error
) {
    @JsonProperty("executionId")
    public String executionId() {
        return id;
    }

    @JsonProperty("nodes")
    public List<NodeExecutionResponse> nodes() {
        return nodeExecutions != null ? nodeExecutions : Collections.emptyList();
    }

    public static ExecutionResponse fromModel(WorkflowExecution model) {
        if (model == null) {
            return null;
        }
        List<NodeExecutionResponse> nodeResponses = model.getNodeExecutions() != null
                ? model.getNodeExecutions().stream().map(NodeExecutionResponse::fromModel).toList()
                : Collections.emptyList();

        return new ExecutionResponse(
                model.getId(),
                model.getWorkflowId(),
                model.getStatus(),
                model.getTriggerType(),
                model.getStartedAt(),
                model.getCompletedAt(),
                model.getDurationMs(),
                nodeResponses,
                model.getError()
        );
    }
}
