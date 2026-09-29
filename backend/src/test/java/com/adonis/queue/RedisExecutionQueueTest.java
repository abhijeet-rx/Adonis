package com.adonis.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisExecutionQueueTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, String, String> streamOperations;

    private ObjectMapper objectMapper;
    private RedisExecutionQueue redisExecutionQueue;
    private static final String STREAM_KEY = "adonis:test:stream";
    private static final String CONSUMER_GROUP = "test-group";
    private static final String CONSUMER_NAME = "test-worker";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        lenient().when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        redisExecutionQueue = new RedisExecutionQueue(
                redisTemplate,
                objectMapper,
                STREAM_KEY,
                CONSUMER_GROUP,
                CONSUMER_NAME
        );
    }

    @Test
    void enqueue_ValidJob_AddsRecordToStream() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-1", "user-1", "manual", Instant.now());
        when(streamOperations.add(eq(STREAM_KEY), anyMap())).thenReturn(RecordId.of("12345-0"));

        redisExecutionQueue.enqueue(job);

        verify(streamOperations).add(eq(STREAM_KEY), argThat(map ->
                map.containsKey("payload") &&
                map.get("payload").contains("\"executionId\":\"exec-1\"") &&
                map.get("payload").contains("\"workflowId\":\"wf-1\"")));
    }

    @Test
    void enqueue_NullJob_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> redisExecutionQueue.enqueue(null));
        verify(streamOperations, never()).add(anyString(), anyMap());
    }

    @Test
    void enqueue_RedisThrowsException_WrapsInQueueException() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-1", "user-1", "manual", Instant.now());
        when(streamOperations.add(anyString(), anyMap()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        QueueException ex = assertThrows(QueueException.class, () -> redisExecutionQueue.enqueue(job));
        assertTrue(ex.getMessage().contains("Redis enqueue operation failed"));
    }

    @Test
    void poll_MessagePresent_DeserializesAndReturnsQueuedJobMessage() {
        String json = "{\"executionId\":\"exec-2\",\"workflowId\":\"wf-2\",\"userId\":\"user-2\",\"triggerType\":\"manual\",\"queuedAt\":\"2026-09-29T12:00:00Z\"}";
        MapRecord<String, String, String> mockRecord = MapRecord.create(
                STREAM_KEY,
                Map.of("payload", json)
        ).withId(RecordId.of("9999-0"));

        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(mockRecord));

        Optional<QueuedJobMessage> result = redisExecutionQueue.poll(Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals("9999-0", result.get().messageId());
        assertEquals("exec-2", result.get().job().executionId());
        assertEquals("wf-2", result.get().job().workflowId());
        assertEquals("user-2", result.get().job().userId());
    }

    @Test
    void poll_TimeoutOrEmpty_ReturnsEmptyOptional() {
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Collections.emptyList());

        Optional<QueuedJobMessage> result = redisExecutionQueue.poll(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
    }

    @Test
    void poll_CorruptJson_QuarantinesToDlqAndAcknowledges() {
        MapRecord<String, String, String> corruptRecord = MapRecord.create(
                STREAM_KEY,
                Map.of("payload", "INVALID_NOT_JSON")
        ).withId(RecordId.of("corrupt-1"));

        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(corruptRecord));

        Optional<QueuedJobMessage> result = redisExecutionQueue.poll(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
        // Verifies quarantined to DLQ stream
        verify(streamOperations).add(eq(STREAM_KEY + ":dlq"), anyMap());
        // Verifies acknowledged from main stream to prevent infinite poison-pill loop
        verify(streamOperations).acknowledge(eq(STREAM_KEY), eq(CONSUMER_GROUP), eq(RecordId.of("corrupt-1")));
    }

    @Test
    void poll_RedisThrowsException_WrapsInQueueException() {
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenThrow(new RedisConnectionFailureException("Connection lost"));

        assertThrows(QueueException.class, () -> redisExecutionQueue.poll(Duration.ofSeconds(1)));
    }

    @Test
    void acknowledge_ValidMessageId_CallsOpsForStreamAcknowledge() {
        redisExecutionQueue.acknowledge("rec-100");

        verify(streamOperations).acknowledge(eq(STREAM_KEY), eq(CONSUMER_GROUP), eq(RecordId.of("rec-100")));
    }

    @Test
    void acknowledge_BlankOrNull_DoesNothing() {
        redisExecutionQueue.acknowledge(null);
        redisExecutionQueue.acknowledge("   ");

        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    void claimPending_ReclaimsStalePendingMessages() {
        PendingMessage pm = mock(PendingMessage.class);
        when(pm.getId()).thenReturn(RecordId.of("stale-1"));
        when(pm.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(5));
        when(pm.getTotalDeliveryCount()).thenReturn(2L);

        PendingMessages pendingMessages = new PendingMessages(
                CONSUMER_GROUP,
                List.of(pm)
        );
        when(streamOperations.pending(eq(STREAM_KEY), eq(CONSUMER_GROUP), any(Range.class), eq(10L)))
                .thenReturn(pendingMessages);

        String json = "{\"executionId\":\"exec-stale\",\"workflowId\":\"wf-stale\",\"userId\":\"user-1\",\"triggerType\":\"manual\",\"queuedAt\":\"2026-09-29T12:00:00Z\"}";
        MapRecord<String, String, String> claimedRecord = MapRecord.create(
                STREAM_KEY,
                Map.of("payload", json)
        ).withId(RecordId.of("stale-1"));

        when(streamOperations.claim(eq(STREAM_KEY), eq(CONSUMER_GROUP), eq(CONSUMER_NAME), any(Duration.class), any(RecordId[].class)))
                .thenReturn(List.of(claimedRecord));

        List<QueuedJobMessage> claimed = redisExecutionQueue.claimPending(Duration.ofMinutes(1), 10);

        assertEquals(1, claimed.size());
        assertEquals("stale-1", claimed.get(0).messageId());
        assertEquals("exec-stale", claimed.get(0).job().executionId());
        assertEquals(2, claimed.get(0).deliveryCount());
    }

    @Test
    void size_ReturnsStreamSize() {
        when(streamOperations.size(STREAM_KEY)).thenReturn(7L);

        assertEquals(7L, redisExecutionQueue.size());
    }

    @Test
    void size_RedisError_ReturnsZeroSafely() {
        when(streamOperations.size(STREAM_KEY)).thenThrow(new RedisConnectionFailureException("Connection lost"));

        assertEquals(0L, redisExecutionQueue.size());
    }
}
