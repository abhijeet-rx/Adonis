package com.adonis.model;

import com.adonis.execution.ExecutionStatus;
import com.adonis.util.SecretRedactor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "workflow_executions")
@CompoundIndexes({
        @CompoundIndex(name = "wf_user_started_idx", def = "{'workflowId': 1, 'userId': 1, 'startedAt': -1}"),
        @CompoundIndex(name = "user_started_idx", def = "{'userId': 1, 'startedAt': -1}"),
        @CompoundIndex(name = "wf_idempotency_idx", def = "{'workflowId': 1, 'idempotencyKey': 1}", unique = true, partialFilter = "{'idempotencyKey': {'$exists': true, '$ne': null}}")
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

    private Instant queuedAt;

    @Indexed
    private Instant startedAt;

    private Instant completedAt;

    private Long durationMs;

    private List<NodeExecution> nodeExecutions = new ArrayList<>();

    private String error;

    private String workerId;

    @Indexed
    private Instant leaseUntil;

    private Instant lastHeartbeatAt;

    @Indexed
    private String scheduledOccurrence;

    private String idempotencyKey;

    private Map<String, Object> triggerPayload = new LinkedHashMap<>();

    public WorkflowExecution() {
    }

    public WorkflowExecution(String id, String workflowId, String userId, ExecutionStatus status,
                             String triggerType, Instant startedAt, Instant completedAt,
                             Long durationMs, List<NodeExecution> nodeExecutions, String error) {
        this(id, workflowId, userId, status, triggerType, null, startedAt, completedAt, durationMs, nodeExecutions, error);
    }

    public WorkflowExecution(String id, String workflowId, String userId, ExecutionStatus status,
                             String triggerType, Instant queuedAt, Instant startedAt, Instant completedAt,
                             Long durationMs, List<NodeExecution> nodeExecutions, String error) {
        this(id, workflowId, userId, status, triggerType, queuedAt, startedAt, completedAt, durationMs, nodeExecutions, error, null, null, null);
    }

    public WorkflowExecution(String id, String workflowId, String userId, ExecutionStatus status,
                             String triggerType, Instant queuedAt, Instant startedAt, Instant completedAt,
                             Long durationMs, List<NodeExecution> nodeExecutions, String error,
                             String workerId, Instant leaseUntil, Instant lastHeartbeatAt) {
        this(id, workflowId, userId, status, triggerType, queuedAt, startedAt, completedAt, durationMs, nodeExecutions, error, workerId, leaseUntil, lastHeartbeatAt, null, null, null);
    }

    public WorkflowExecution(String id, String workflowId, String userId, ExecutionStatus status,
                             String triggerType, Instant queuedAt, Instant startedAt, Instant completedAt,
                             Long durationMs, List<NodeExecution> nodeExecutions, String error,
                             String workerId, Instant leaseUntil, Instant lastHeartbeatAt,
                             String scheduledOccurrence, String idempotencyKey, Map<String, Object> triggerPayload) {
        this.id = id;
        this.workflowId = workflowId;
        this.userId = userId;
        this.status = status;
        this.triggerType = triggerType;
        this.queuedAt = queuedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.nodeExecutions = nodeExecutions != null ? new ArrayList<>(nodeExecutions) : new ArrayList<>();
        this.error = SecretRedactor.redactString(error);
        this.workerId = workerId;
        this.leaseUntil = leaseUntil;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.scheduledOccurrence = scheduledOccurrence;
        this.idempotencyKey = idempotencyKey;
        this.triggerPayload = triggerPayload != null ? SecretRedactor.redactMap(triggerPayload) : new LinkedHashMap<>();
    }

    public static WorkflowExecution queued(String workflowId, String userId, String triggerType) {
        Instant now = Instant.now();
        return new WorkflowExecution(
                null,
                workflowId,
                userId,
                ExecutionStatus.QUEUED,
                triggerType != null ? triggerType : "MANUAL",
                now,
                null,
                null,
                null,
                new ArrayList<>(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static WorkflowExecution start(String workflowId, String userId, String triggerType) {
        Instant now = Instant.now();
        return new WorkflowExecution(
                null,
                workflowId,
                userId,
                ExecutionStatus.RUNNING,
                triggerType != null ? triggerType : "MANUAL",
                now,
                now,
                null,
                null,
                new ArrayList<>(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public void markRunning(Instant startedAt) {
        markRunning(startedAt, null, null);
    }

    public void markRunning(Instant startedAt, String workerId, Instant leaseUntil) {
        if (this.status != ExecutionStatus.QUEUED) {
            throw new IllegalStateException("Cannot transition to RUNNING from " + this.status);
        }
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.workerId = workerId;
        this.leaseUntil = leaseUntil;
        this.lastHeartbeatAt = this.startedAt;
    }

    public void markQueueFailed(String errorMessage) {
        this.status = ExecutionStatus.FAILED;
        this.completedAt = Instant.now();
        this.durationMs = 0L;
        this.error = SecretRedactor.redactString(errorMessage != null ? errorMessage : "Failed to queue execution");
        this.leaseUntil = null;
    }

    public void markSuccess(Instant completedAt, List<NodeExecution> nodes) {
        this.status = ExecutionStatus.SUCCESS;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
        this.durationMs = (this.startedAt != null) ? Duration.between(this.startedAt, this.completedAt).toMillis() : 0L;
        this.nodeExecutions = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
        this.error = null;
        this.leaseUntil = null;
    }

    public void markFailed(Instant completedAt, List<NodeExecution> nodes, String errorMessage) {
        this.status = ExecutionStatus.FAILED;
        this.completedAt = completedAt != null ? completedAt : Instant.now();
        this.durationMs = (this.startedAt != null) ? Duration.between(this.startedAt, this.completedAt).toMillis() : 0L;
        this.nodeExecutions = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
        this.error = SecretRedactor.redactString(errorMessage != null ? errorMessage : "Workflow execution failed");
        this.leaseUntil = null;
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

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(Instant queuedAt) {
        this.queuedAt = queuedAt;
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
        this.error = SecretRedactor.redactString(error);
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public String getScheduledOccurrence() {
        return scheduledOccurrence;
    }

    public void setScheduledOccurrence(String scheduledOccurrence) {
        this.scheduledOccurrence = scheduledOccurrence;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Map<String, Object> getTriggerPayload() {
        return triggerPayload != null ? triggerPayload : new LinkedHashMap<>();
    }

    public void setTriggerPayload(Map<String, Object> triggerPayload) {
        this.triggerPayload = triggerPayload != null ? SecretRedactor.redactMap(triggerPayload) : new LinkedHashMap<>();
    }
}
