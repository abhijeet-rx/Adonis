package com.adonis.execution;

import com.adonis.model.NodeExecutionAttempt;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
        String error,
        int retryCount,
        List<NodeExecutionAttempt> attempts
) {

    public NodeExecutionResult {
        if (input == null) input = Collections.emptyMap();
        if (output == null) output = Collections.emptyMap();
        if (attempts == null) attempts = Collections.emptyList();
    }

    public NodeExecutionResult(
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
        this(nodeId, nodeType, status, startedAt, completedAt, durationMs, input, output, error, 0, Collections.emptyList());
    }

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
        this(nodeId, nodeType, status, startedAt, completedAt, durationMs, Collections.emptyMap(), output, error, 0, Collections.emptyList());
    }

    public static NodeExecutionResult success(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> output) {
        return success(nodeId, nodeType, startedAt, completedAt, Collections.emptyMap(), output, 0, Collections.emptyList());
    }

    public static NodeExecutionResult success(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            Map<String, Object> output) {
        return success(nodeId, nodeType, startedAt, completedAt, input, output, 0, Collections.emptyList());
    }

    public static NodeExecutionResult success(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            Map<String, Object> output,
            int retryCount,
            List<NodeExecutionAttempt> attempts) {
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
                null,
                retryCount,
                attempts != null ? attempts : Collections.emptyList()
        );
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            String error) {
        return failure(nodeId, nodeType, startedAt, completedAt, Collections.emptyMap(), Collections.emptyMap(), error, 0, Collections.emptyList());
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            String error) {
        return failure(nodeId, nodeType, startedAt, completedAt, input, Collections.emptyMap(), error, 0, Collections.emptyList());
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            Map<String, Object> output,
            String error) {
        return failure(nodeId, nodeType, startedAt, completedAt, input, output, error, 0, Collections.emptyList());
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            String error,
            int retryCount,
            List<NodeExecutionAttempt> attempts) {
        return failure(nodeId, nodeType, startedAt, completedAt, input, Collections.emptyMap(), error, retryCount, attempts);
    }

    public static NodeExecutionResult failure(
            String nodeId,
            String nodeType,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            Map<String, Object> output,
            String error,
            int retryCount,
            List<NodeExecutionAttempt> attempts) {
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
                output != null ? output : Collections.emptyMap(),
                error != null ? error : "Unknown execution failure",
                retryCount,
                attempts != null ? attempts : Collections.emptyList()
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
                null,
                0,
                Collections.emptyList()
        );
    }
}
