package com.adonis.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Durable record of a scheduled workflow occurrence used to guarantee idempotency
 * and duplicate protection across multiple concurrent scheduler instances.
 */
@Document(collection = "scheduled_occurrences")
@CompoundIndexes({
        @CompoundIndex(name = "wf_fire_time_idx", def = "{'workflowId': 1, 'scheduledFireTime': 1}", unique = true)
})
public class ScheduledOccurrence {

    @Id
    private String id; // Deterministic occurrence key: workflowId + ":" + scheduledFireTimeEpochMs

    @Indexed
    private String workflowId;

    private Instant scheduledFireTime;

    private String executionId;

    private Instant createdAt;

    public ScheduledOccurrence() {
    }

    public ScheduledOccurrence(String id, String workflowId, Instant scheduledFireTime, String executionId, Instant createdAt) {
        this.id = id;
        this.workflowId = workflowId;
        this.scheduledFireTime = scheduledFireTime;
        this.executionId = executionId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static String buildOccurrenceKey(String workflowId, Instant scheduledFireTime) {
        return workflowId + ":" + scheduledFireTime.toEpochMilli();
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

    public Instant getScheduledFireTime() {
        return scheduledFireTime;
    }

    public void setScheduledFireTime(Instant scheduledFireTime) {
        this.scheduledFireTime = scheduledFireTime;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
