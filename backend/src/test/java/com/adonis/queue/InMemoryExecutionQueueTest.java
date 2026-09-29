package com.adonis.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryExecutionQueueTest {

    private InMemoryExecutionQueue queue;

    @BeforeEach
    void setUp() {
        queue = new InMemoryExecutionQueue();
    }

    @Test
    void enqueueAndPoll_FIFOOrderPreserved() {
        ExecutionJob job1 = new ExecutionJob("exec-1", "wf-1", "user-1", "manual", Instant.now());
        ExecutionJob job2 = new ExecutionJob("exec-2", "wf-2", "user-1", "manual", Instant.now());

        queue.enqueue(job1);
        queue.enqueue(job2);

        assertEquals(2, queue.size());

        Optional<QueuedJobMessage> poll1 = queue.poll(Duration.ofMillis(100));
        assertTrue(poll1.isPresent());
        assertEquals("exec-1", poll1.get().job().executionId());
        assertNotNull(poll1.get().messageId());
        assertEquals(1, poll1.get().deliveryCount());

        Optional<QueuedJobMessage> poll2 = queue.poll(Duration.ofMillis(100));
        assertTrue(poll2.isPresent());
        assertEquals("exec-2", poll2.get().job().executionId());

        // In-flight messages remain in size until acknowledged
        assertEquals(2, queue.pendingCount());

        queue.acknowledge(poll1.get().messageId());
        queue.acknowledge(poll2.get().messageId());

        assertEquals(0, queue.pendingCount());
        assertEquals(0, queue.size());
    }

    @Test
    void acknowledge_RemovesFromInFlight() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-1", "user-1", "manual", Instant.now());
        queue.enqueue(job);

        Optional<QueuedJobMessage> poll = queue.poll(Duration.ofMillis(100));
        assertTrue(poll.isPresent());
        assertTrue(queue.isPending(poll.get().messageId()));

        queue.acknowledge(poll.get().messageId());
        assertFalse(queue.isPending(poll.get().messageId()));
    }

    @Test
    void claimPending_ReclaimsStaleMessagesAfterIdleTimeout() throws InterruptedException {
        ExecutionJob job = new ExecutionJob("exec-crash", "wf-1", "user-1", "manual", Instant.now());
        queue.enqueue(job);

        Optional<QueuedJobMessage> poll = queue.poll(Duration.ofMillis(100));
        assertTrue(poll.isPresent());
        String messageId = poll.get().messageId();

        // Immediately claiming with 1-second idle threshold returns empty
        List<QueuedJobMessage> claimedImmediate = queue.claimPending(Duration.ofSeconds(1), 10);
        assertTrue(claimedImmediate.isEmpty());

        // Claiming with 0 or negative idle threshold immediately reclaims
        List<QueuedJobMessage> claimed = queue.claimPending(Duration.ofMillis(0), 10);
        assertEquals(1, claimed.size());
        assertEquals(messageId, claimed.get(0).messageId());
        assertEquals("exec-crash", claimed.get(0).job().executionId());
        assertEquals(2, claimed.get(0).deliveryCount()); // Second delivery
    }

    @Test
    void poll_EmptyQueue_ReturnsEmptyOptional() {
        Optional<QueuedJobMessage> result = queue.poll(Duration.ofMillis(10));
        assertTrue(result.isEmpty());
    }

    @Test
    void enqueue_NullJob_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(null));
    }
}
