package com.adonis.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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

        Optional<ExecutionJob> poll1 = queue.poll(Duration.ofMillis(100));
        assertTrue(poll1.isPresent());
        assertEquals("exec-1", poll1.get().executionId());

        Optional<ExecutionJob> poll2 = queue.poll(Duration.ofMillis(100));
        assertTrue(poll2.isPresent());
        assertEquals("exec-2", poll2.get().executionId());

        assertEquals(0, queue.size());
    }

    @Test
    void poll_EmptyQueue_ReturnsEmptyOptional() {
        Optional<ExecutionJob> result = queue.poll(Duration.ofMillis(10));
        assertTrue(result.isEmpty());
    }

    @Test
    void enqueue_NullJob_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(null));
    }
}
