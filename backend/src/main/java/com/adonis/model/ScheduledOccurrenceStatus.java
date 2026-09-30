package com.adonis.model;

/**
 * Lifecycle states for a durable scheduled occurrence.
 */
public enum ScheduledOccurrenceStatus {
    /**
     * The scheduler has claimed/reserved this scheduled fire time.
     * Prevents any other scheduler from creating a duplicate execution for (workflowId, scheduledFireTime).
     */
    CLAIMED,

    /**
     * The execution has been successfully submitted to the message broker (Redis Streams).
     */
    ENQUEUED,

    /**
     * The occurrence was claimed but queue submission failed due to a transient infrastructure error.
     * The occurrence remains recoverable by subsequent scheduler iterations without creating duplicate executions.
     */
    FAILED_RETRYABLE
}
