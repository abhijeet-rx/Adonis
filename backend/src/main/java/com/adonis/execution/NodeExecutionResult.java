package com.adonis.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record NodeExecutionResult(
        String nodeId,
        String nodeType,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        Map<String, Object> output,
        String error
) {
    public static NodeExecutionResult success(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> output) {
        long duration = Duration.between(startedAt, completedAt).toMillis();
        return new NodeExecutionResult(
                nodeId,
                nodeType,
                ExecutionStatus.SUCCESS,
                startedAt,
                completedAt,
                duration,
                output != null ? output : Map.of(),
                null
        );
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            String error) {
        long duration = Duration.between(startedAt, completedAt).toMillis();
        return new NodeExecutionResult(
                nodeId,
                nodeType,
                ExecutionStatus.FAILED,
                startedAt,
                completedAt,
                duration,
                null,
                error != null ? error : "Unknown execution failure"
        );
    }
}
