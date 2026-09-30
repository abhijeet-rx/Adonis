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
    private final TriggerContext triggerContext;
    private final Map<String, NodeExecutionResult> nodeResults = new LinkedHashMap<>();

    public ExecutionContext(String executionId, String workflowId, String userId, Instant startedAt) {
        this(executionId, workflowId, userId, startedAt, TriggerContext.manual(userId));
    }

    public ExecutionContext(String executionId, String workflowId, String userId, Instant startedAt, TriggerContext triggerContext) {
        this.executionId = executionId;
        this.workflowId = workflowId;
        this.userId = userId;
        this.startedAt = startedAt;
        this.triggerContext = triggerContext != null ? triggerContext : TriggerContext.manual(userId);
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

    public TriggerContext getTriggerContext() {
        return triggerContext;
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
