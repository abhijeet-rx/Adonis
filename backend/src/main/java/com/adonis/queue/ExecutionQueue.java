package com.adonis.queue;

import java.time.Duration;
import java.util.Optional;

/**
 * Queue abstraction for workflow execution jobs.
 * Decouples the application and execution services from concrete Redis queueing details.
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
     * Polls the next execution job from the queue with an optional timeout.
     *
     * @param timeout polling duration timeout
     * @return Optional containing the next ExecutionJob, or empty if timed out
     */
    Optional<ExecutionJob> poll(Duration timeout);

    /**
     * Returns the approximate number of pending execution jobs in the queue.
     *
     * @return current queue size
     */
    long size();
}
