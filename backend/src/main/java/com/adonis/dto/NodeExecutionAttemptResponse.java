package com.adonis.dto;

import com.adonis.execution.ExecutionStatus;
import com.adonis.model.NodeExecutionAttempt;
import com.adonis.util.SecretRedactor;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record NodeExecutionAttemptResponse(
        int attemptNumber,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Map<String, Object> input,
        Map<String, Object> output,
        String error
) {
    public static NodeExecutionAttemptResponse fromModel(NodeExecutionAttempt model) {
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

        return new NodeExecutionAttemptResponse(
                model.getAttemptNumber(),
                model.getStatus(),
                model.getStartedAt(),
                model.getCompletedAt(),
                model.getDurationMs(),
                sanitizedInput,
                sanitizedOutput,
                sanitizedError
        );
    }
}
