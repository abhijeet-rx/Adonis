package com.adonis.dto;

import com.adonis.execution.ExecutionStatus;
import com.adonis.model.NodeExecution;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record NodeExecutionResponse(
        String nodeId,
        String nodeType,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Map<String, Object> input,
        Map<String, Object> output,
        String error
) {
    public static NodeExecutionResponse fromModel(NodeExecution model) {
        if (model == null) {
            return null;
        }
        return new NodeExecutionResponse(
                model.getNodeId(),
                model.getNodeType(),
                model.getStatus(),
                model.getStartedAt(),
                model.getCompletedAt(),
                model.getDurationMs(),
                model.getInput() != null ? model.getInput() : Collections.emptyMap(),
                model.getOutput() != null ? model.getOutput() : Collections.emptyMap(),
                model.getError()
        );
    }
}
