package com.adonis.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionJobTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void constructor_ValidArguments_CreatesInstance() {
        Instant now = Instant.now();
        ExecutionJob job = new ExecutionJob("exec-1", "wf-1", "user-1", "manual", now);

        assertEquals("exec-1", job.executionId());
        assertEquals("wf-1", job.workflowId());
        assertEquals("user-1", job.userId());
        assertEquals("manual", job.triggerType());
        assertEquals(now, job.queuedAt());
    }

    @Test
    void constructor_NullTriggerAndQueuedAt_AppliesDefaults() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-1", "user-1", null, null);

        assertEquals("manual", job.triggerType());
        assertNotNull(job.queuedAt());
    }

    @Test
    void constructor_NullOrBlankFields_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionJob(null, "wf-1", "user-1", "manual", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionJob(" ", "wf-1", "user-1", "manual", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionJob("exec-1", null, "user-1", "manual", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionJob("exec-1", "", "user-1", "manual", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionJob("exec-1", "wf-1", null, "manual", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionJob("exec-1", "wf-1", "   ", "manual", Instant.now()));
    }

    @Test
    void jsonSerializationAndDeserialization_WorksDeterministically() throws Exception {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        ExecutionJob original = new ExecutionJob("exec-100", "wf-200", "user-300", "webhook", now);

        String json = objectMapper.writeValueAsString(original);
        assertTrue(json.contains("\"executionId\":\"exec-100\""));
        assertTrue(json.contains("\"workflowId\":\"wf-200\""));
        assertTrue(json.contains("\"userId\":\"user-300\""));
        assertTrue(json.contains("\"triggerType\":\"webhook\""));

        ExecutionJob deserialized = objectMapper.readValue(json, ExecutionJob.class);
        assertEquals(original.executionId(), deserialized.executionId());
        assertEquals(original.workflowId(), deserialized.workflowId());
        assertEquals(original.userId(), deserialized.userId());
        assertEquals(original.triggerType(), deserialized.triggerType());
        assertEquals(original.queuedAt(), deserialized.queuedAt());
    }
}
