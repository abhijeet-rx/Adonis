package com.adonis.queue;

import java.util.Objects;

/**
 * Encapsulates a workflow execution job along with its underlying queue
 * message identifier (e.g., Redis Stream RecordId) and delivery attempt metadata.
 *
 * @param messageId unique identifier assigned by the underlying queue infrastructure
 * @param job the deserialized execution job payload
 * @param deliveryCount number of times this message has been delivered to a worker
 */
public record QueuedJobMessage(
        String messageId,
        ExecutionJob job,
        int deliveryCount
) {
    public QueuedJobMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(job, "job must not be null");
        if (deliveryCount < 1) {
            deliveryCount = 1;
        }
    }

    public QueuedJobMessage(String messageId, ExecutionJob job) {
        this(messageId, job, 1);
    }
}
