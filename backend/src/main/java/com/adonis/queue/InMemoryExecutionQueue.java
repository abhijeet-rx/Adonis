package com.adonis.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory queue implementation for development and testing environments.
 * Simulates at-least-once delivery, unacknowledged in-flight tracking, and pending message recovery
 * without requiring an external running Redis server.
 */
@Component
@ConditionalOnProperty(name = "adonis.queue.type", havingValue = "in-memory")
public class InMemoryExecutionQueue implements ExecutionQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryExecutionQueue.class);

    private final BlockingQueue<QueuedJobMessage> queue = new LinkedBlockingQueue<>();
    private final ConcurrentMap<String, InFlightEntry> pendingEntries = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    public record InFlightEntry(QueuedJobMessage message, Instant deliveredAt, AtomicInteger deliveryCount) {}

    @Override
    public void enqueue(ExecutionJob job) {
        if (job == null) {
            throw new IllegalArgumentException("ExecutionJob must not be null");
        }
        String messageId = "mem-" + idGenerator.incrementAndGet();
        QueuedJobMessage message = new QueuedJobMessage(messageId, job, 1);
        queue.offer(message);
        log.info("Enqueued execution job to in-memory queue: executionId={}, messageId={}",
                job.executionId(), messageId);
    }

    @Override
    public Optional<QueuedJobMessage> poll(Duration timeout) {
        try {
            long timeoutMillis = timeout != null ? timeout.toMillis() : 1000;
            QueuedJobMessage msg = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (msg != null) {
                pendingEntries.put(msg.messageId(), new InFlightEntry(msg, Instant.now(), new AtomicInteger(1)));
                return Optional.of(msg);
            }
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public void acknowledge(String messageId) {
        if (messageId != null) {
            InFlightEntry removed = pendingEntries.remove(messageId);
            if (removed != null) {
                log.debug("Acknowledged in-memory message: {}", messageId);
            }
        }
    }

    @Override
    public List<QueuedJobMessage> claimPending(Duration minIdleTime, int count) {
        List<QueuedJobMessage> claimed = new ArrayList<>();
        Duration effectiveIdle = minIdleTime != null ? minIdleTime : Duration.ofMinutes(1);
        Instant now = Instant.now();

        for (Map.Entry<String, InFlightEntry> entry : pendingEntries.entrySet()) {
            if (claimed.size() >= count) {
                break;
            }
            InFlightEntry inFlight = entry.getValue();
            Duration elapsed = Duration.between(inFlight.deliveredAt(), now);
            if (elapsed.compareTo(effectiveIdle) >= 0) {
                int deliveries = inFlight.deliveryCount().incrementAndGet();
                // Update deliveredAt timestamp for the claimed entry
                pendingEntries.put(entry.getKey(), new InFlightEntry(inFlight.message(), now, inFlight.deliveryCount()));
                claimed.add(new QueuedJobMessage(inFlight.message().messageId(), inFlight.message().job(), deliveries));
            }
        }
        return claimed;
    }

    @Override
    public long size() {
        return queue.size() + pendingEntries.size();
    }

    public int pendingCount() {
        return pendingEntries.size();
    }

    public boolean isPending(String messageId) {
        return pendingEntries.containsKey(messageId);
    }

    public void clear() {
        queue.clear();
        pendingEntries.clear();
    }
}
