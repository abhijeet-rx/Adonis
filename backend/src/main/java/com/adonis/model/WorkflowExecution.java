package com.adonis.model;

import com.adonis.execution.ExecutionStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "workflow_executions")
@CompoundIndexes({
        @CompoundIndex(name = "wf_user_started_idx", def = "{'workflowId': 1, 'userId': 1, 'startedAt': -1}"),
        @CompoundIndex(name = "user_started_idx", def = "{'userId': 1, 'startedAt': -1}")
})
public class WorkflowExecution {

    @Id
    private String id;

    @Indexed
    private String workflowId;

    @Indexed
    private String userId;

    private ExecutionStatus status;

    private String triggerType;

    @Indexed
    private Instant startedAt;

    private Instant completedAt;

    private Long durationMs;

    private List<NodeExecution> nodeExecutions = new ArrayList<>();

    private String error;

    public WorkflowExecution() {
    }

    public WorkflowExecution(String id, String workflowId, String userId, ExecutionStatus status,
                             String triggerType, Instant startedAt, Instant completedAt,
                             Long durationMs, List<NodeExecution> nodeExecutions, String error) {
        this.id = id;
        this.workflowId = workflowId;
        this.userId = userId;
        this.status = status;
        this.triggerType = triggerType;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.nodeExecutions = nodeExecutions != null ? new ArrayList<>(nodeExecutions) : new ArrayList<>();
        this.error = error;
    }

    public static WorkflowExecution start(String workflowId, String userId, String triggerType) {
        Instant now = Instant.now();
        return new WorkflowExecution(
                null,
                workflowId,
                userId,
                ExecutionStatus.RUNNING,
                triggerType != null ? triggerType : "manual",
                now,
                null,
                null,
                new ArrayList<>(),
                null
        );
    }

    public void markSuccess(Instant completedAt, List<NodeExecution> nodes) {
        this.status = ExecutionStatus.SUCCESS;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
        this.durationMs = (this.startedAt != null) ? Duration.between(this.startedAt, this.completedAt).toMillis() : 0L;
        this.nodeExecutions = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
        this.error = null;
    }

    public void markFailed(Instant completedAt, List<NodeExecution> nodes, String errorMessage) {
        this.status = ExecutionStatus.FAILED;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
        this.durationMs = (this.startedAt != null) ? Duration.between(this.startedAt, this.completedAt).toMillis() : 0L;
        this.nodeExecutions = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
        this.error = errorMessage != null ? errorMessage : "Workflow execution failed";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
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

    public List<NodeExecution> getNodeExecutions() {
        return nodeExecutions != null ? nodeExecutions : new ArrayList<>();
    }

    public void setNodeExecutions(List<NodeExecution> nodeExecutions) {
        this.nodeExecutions = nodeExecutions != null ? new ArrayList<>(nodeExecutions) : new ArrayList<>();
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
