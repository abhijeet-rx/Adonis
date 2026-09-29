package com.adonis.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe in-memory queue implementation for development and testing environments
 * where a live Redis server is not provisioned.
 */
@Component
@ConditionalOnProperty(name = "adonis.queue.type", havingValue = "in-memory")
public class InMemoryExecutionQueue implements ExecutionQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryExecutionQueue.class);
    private final BlockingQueue<ExecutionJob> queue = new LinkedBlockingQueue<>();

    @Override
    public void enqueue(ExecutionJob job) {
        if (job == null) {
            throw new IllegalArgumentException("ExecutionJob must not be null");
        }
        queue.offer(job);
        log.info("Enqueued execution job to in-memory queue: executionId={}, workflowId={}",
                job.executionId(), job.workflowId());
    }

    @Override
    public Optional<ExecutionJob> poll(Duration timeout) {
        try {
            long timeoutMillis = timeout != null ? timeout.toMillis() : 1000;
            ExecutionJob job = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(job);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public long size() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
    }
}
