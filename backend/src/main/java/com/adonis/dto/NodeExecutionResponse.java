package com.adonis.dto;

import com.adonis.execution.ExecutionStatus;
import com.adonis.model.NodeExecution;
import com.adonis.util.SecretRedactor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
        String error,
        Integer retryCount,
        List<NodeExecutionAttemptResponse> attempts
) {
    public NodeExecutionResponse(
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
        this(nodeId, nodeType, status, startedAt, completedAt, durationMs, input, output, error, 0, Collections.emptyList());
    }

    @JsonProperty("attempts")
    public List<NodeExecutionAttemptResponse> attempts() {
        return attempts != null ? attempts : Collections.emptyList();
    }

    public static NodeExecutionResponse fromModel(NodeExecution model) {
        if (model == null) {
            return null;
        }
        Map<String, Object> sanitizedInput = SecretRedactor.redactMap(
                model.getInput() != null ? model.getInput() : Collections.emptyMap()
        );
        Map<String, Object> sanitizedOutput = SecretRedactor.redactMap(
                model.getOutput() != null ? model.getOutput() : Collections.emptyMap()
        );
        String sanitizedError = SecretRedactor.redactString(model.getError());

        List<NodeExecutionAttemptResponse> attemptResponses = model.getAttempts() != null
                ? model.getAttempts().stream().map(NodeExecutionAttemptResponse::fromModel).toList()
                : Collections.emptyList();

        return new NodeExecutionResponse(
                model.getNodeId(),
                model.getNodeType(),
                model.getStatus(),
                model.getStartedAt(),
                model.getCompletedAt(),
                model.getDurationMs(),
                sanitizedInput,
                sanitizedOutput,
                sanitizedError,
                model.getRetryCount() != null ? model.getRetryCount() : 0,
                attemptResponses
        );
    }
}
