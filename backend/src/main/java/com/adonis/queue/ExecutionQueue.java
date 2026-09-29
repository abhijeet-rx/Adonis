package com.adonis.queue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Queue abstraction for workflow execution jobs.
 * Decouples the application and execution services from concrete Redis queueing details.
 * Supports reliable at-least-once message delivery, explicit message acknowledgement,
 * and recovery of unacknowledged pending messages.
 */
public interface ExecutionQueue {

    /**
     * Enqueues an execution job onto the queue.
     *
     * @param job the execution job payload
     * @throws QueueException if enqueuing fails
     */
    void enqueue(ExecutionJob job);

    /**
     * Polls the next execution job message from the queue with a timeout.
     *
     * @param timeout polling duration timeout
     * @return Optional containing the next QueuedJobMessage, or empty if timed out
     */
    Optional<QueuedJobMessage> poll(Duration timeout);

    /**
     * Acknowledges that a message has been successfully processed and persisted.
     * In Redis Streams, this executes XACK to remove the message from the consumer group's PEL.
     *
     * @param messageId the message identifier to acknowledge
     */
    void acknowledge(String messageId);

    /**
     * Reclaims or queries stale pending messages that have been delivered to a worker
     * but not acknowledged within the specified idle duration (e.g. due to worker crash).
     *
     * @param minIdleTime minimum idle time before an unacknowledged message can be claimed
     * @param count maximum number of pending messages to reclaim
     * @return list of claimed QueuedJobMessages
     */
    List<QueuedJobMessage> claimPending(Duration minIdleTime, int count);

    /**
     * Returns the approximate number of pending execution jobs or stream entries in the queue.
     *
     * @return current queue size
     */
    long size();
}
