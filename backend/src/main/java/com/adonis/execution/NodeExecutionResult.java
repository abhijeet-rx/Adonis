package com.adonis.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record NodeExecutionResult(
        String nodeId,
        String nodeType,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        Map<String, Object> input,
        Map<String, Object> output,
        String error
) {

    public NodeExecutionResult(
            String nodeId,
            String nodeType,
            ExecutionStatus status,
            Instant startedAt,
            Instant completedAt,
            long durationMs,
            Map<String, Object> output,
            String error
    ) {
        this(nodeId, nodeType, status, startedAt, completedAt, durationMs, Collections.emptyMap(), output, error);
    }

    public static NodeExecutionResult success(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> output) {
        return success(nodeId, nodeType, startedAt, completedAt, Collections.emptyMap(), output);
    }

    public static NodeExecutionResult success(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            Map<String, Object> output) {
        long duration = (startedAt != null && completedAt != null)
                ? Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new NodeExecutionResult(
                nodeId,
                nodeType,
                ExecutionStatus.SUCCESS,
                startedAt,
                completedAt,
                duration,
                input != null ? input : Collections.emptyMap(),
                output != null ? output : Collections.emptyMap(),
                null
        );
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            String error) {
        return failure(nodeId, nodeType, startedAt, completedAt, Collections.emptyMap(), error);
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            String error) {
        long duration = (startedAt != null && completedAt != null)
                ? Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new NodeExecutionResult(
                nodeId,
                nodeType,
                ExecutionStatus.FAILED,
                startedAt,
                completedAt,
                duration,
                input != null ? input : Collections.emptyMap(),
                Collections.emptyMap(),
                error != null ? error : "Unknown execution failure"
        );
    }

    public static NodeExecutionResult skipped(String nodeId, String nodeType) {
        return new NodeExecutionResult(
                nodeId,
                nodeType,
                ExecutionStatus.SKIPPED,
                null,
                null,
                0L,
                Collections.emptyMap(),
                Collections.emptyMap(),
                null
        );
    }
}
