package com.adonis.execution;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionContext {

    private final String executionId;
    private final String workflowId;
    private final String userId;
    private final Instant startedAt;
    private final Map<String, NodeExecutionResult> nodeResults = new LinkedHashMap<>();

    public ExecutionContext(String executionId, String workflowId, String userId, Instant startedAt) {
        this.executionId = executionId;
        this.workflowId = workflowId;
        this.userId = userId;
        this.startedAt = startedAt;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Map<String, NodeExecutionResult> getNodeResults() {
        return Collections.unmodifiableMap(nodeResults);
    }

    public void recordNodeResult(String nodeId, NodeExecutionResult result) {
        this.nodeResults.put(nodeId, result);
    }

    public NodeExecutionResult getNodeResult(String nodeId) {
        return this.nodeResults.get(nodeId);
    }
}
