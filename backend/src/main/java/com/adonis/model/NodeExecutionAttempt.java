package com.adonis.model;

import com.adonis.execution.ExecutionStatus;
import com.adonis.util.SecretRedactor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks an individual execution attempt for a node, preserving full telemetry across retries.
 */
public class NodeExecutionAttempt {

    private int attemptNumber;
    private ExecutionStatus status;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Map<String, Object> output = new LinkedHashMap<>();
    private String error;

    public NodeExecutionAttempt() {
    }

    public NodeExecutionAttempt(
            int attemptNumber,
            ExecutionStatus status,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            Map<String, Object> input,
            Map<String, Object> output,
            String error) {
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.input = SecretRedactor.redactMap(input);
        this.output = SecretRedactor.redactMap(output);
        this.error = SecretRedactor.redactString(error);
    }

    public static NodeExecutionAttempt success(
            int attemptNumber,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            Map<String, Object> output) {
        long duration = (startedAt != null && completedAt != null)
                ? Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new NodeExecutionAttempt(
                attemptNumber,
                ExecutionStatus.SUCCESS,
                startedAt,
                completedAt,
                duration,
                SecretRedactor.redactMap(input),
                SecretRedactor.redactMap(output),
                null
        );
    }

    public static NodeExecutionAttempt failure(
            int attemptNumber,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> input,
            Map<String, Object> output,
            String error) {
        long duration = (startedAt != null && completedAt != null)
                ? Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new NodeExecutionAttempt(
                attemptNumber,
                ExecutionStatus.FAILED,
                startedAt,
                completedAt,
                duration,
                SecretRedactor.redactMap(input),
                SecretRedactor.redactMap(output != null ? output : Collections.emptyMap()),
                SecretRedactor.redactString(error != null ? error : "Attempt failed")
        );
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = SecretRedactor.redactMap(input);
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = SecretRedactor.redactMap(output);
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = SecretRedactor.redactString(error);
    }
}
