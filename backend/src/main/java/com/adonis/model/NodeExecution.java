package com.adonis.model;

import com.adonis.execution.ExecutionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class NodeExecution {

    private String nodeId;
    private String nodeType;
    private ExecutionStatus status;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Map<String, Object> output = new LinkedHashMap<>();
    private String error;

    public NodeExecution() {
    }

    public NodeExecution(String nodeId, String nodeType, ExecutionStatus status,
                         Instant startedAt, Instant completedAt, Long durationMs,
                         Map<String, Object> input, Map<String, Object> output, String error) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.input = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
        this.output = output != null ? new LinkedHashMap<>(output) : new LinkedHashMap<>();
        this.error = error;
    }

    public static NodeExecution success(String nodeId, String nodeType,
                                        Instant startedAt, Instant completedAt,
                                        Map<String, Object> input, Map<String, Object> output) {
        Long duration = (startedAt != null && completedAt != null)
                ? Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new NodeExecution(
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

    public static NodeExecution failure(String nodeId, String nodeType,
                                        Instant startedAt, Instant completedAt,
                                        Map<String, Object> input, String error) {
        Long duration = (startedAt != null && completedAt != null)
                ? Duration.between(startedAt, completedAt).toMillis()
                : 0L;
        return new NodeExecution(
                nodeId,
                nodeType,
                ExecutionStatus.FAILED,
                startedAt,
                completedAt,
                duration,
                input != null ? input : Collections.emptyMap(),
                Collections.emptyMap(),
                error != null ? error : "Node execution failed"
        );
    }

    public static NodeExecution skipped(String nodeId, String nodeType) {
        return new NodeExecution(
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

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
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
        this.input = input != null ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output != null ? new LinkedHashMap<>(output) : new LinkedHashMap<>();
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
