package com.adonis.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisExecutionQueueTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    private ObjectMapper objectMapper;
    private RedisExecutionQueue redisExecutionQueue;
    private static final String QUEUE_KEY = "adonis:test:queue";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        org.mockito.Mockito.lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        redisExecutionQueue = new RedisExecutionQueue(redisTemplate, objectMapper, QUEUE_KEY);
    }

    @Test
    void enqueue_ValidJob_PushesJsonToRedisList() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-1", "user-1", "manual", Instant.now());

        redisExecutionQueue.enqueue(job);

        verify(listOperations).rightPush(eq(QUEUE_KEY), argThat(payload ->
                payload.contains("\"executionId\":\"exec-1\"") && payload.contains("\"workflowId\":\"wf-1\"")));
    }

    @Test
    void enqueue_NullJob_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> redisExecutionQueue.enqueue(null));
        verify(listOperations, never()).rightPush(anyString(), anyString());
    }

    @Test
    void enqueue_RedisThrowsException_WrapsInQueueException() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-1", "user-1", "manual", Instant.now());
        when(listOperations.rightPush(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        QueueException ex = assertThrows(QueueException.class, () -> redisExecutionQueue.enqueue(job));
        assertTrue(ex.getMessage().contains("Redis enqueue operation failed"));
    }

    @Test
    void poll_MessagePresent_DeserializesAndReturnsJob() {
        String json = "{\"executionId\":\"exec-2\",\"workflowId\":\"wf-2\",\"userId\":\"user-2\",\"triggerType\":\"manual\",\"queuedAt\":\"2026-09-29T12:00:00Z\"}";
        when(listOperations.leftPop(eq(QUEUE_KEY), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(json);

        Optional<ExecutionJob> result = redisExecutionQueue.poll(Duration.ofSeconds(2));

        assertTrue(result.isPresent());
        assertEquals("exec-2", result.get().executionId());
        assertEquals("wf-2", result.get().workflowId());
        assertEquals("user-2", result.get().userId());
    }

    @Test
    void poll_TimeoutOrEmpty_ReturnsEmptyOptional() {
        when(listOperations.leftPop(eq(QUEUE_KEY), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(null);

        Optional<ExecutionJob> result = redisExecutionQueue.poll(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
    }

    @Test
    void poll_CorruptJson_DiscardsMessageAndReturnsEmptyOptional() {
        when(listOperations.leftPop(eq(QUEUE_KEY), anyLong(), eq(TimeUnit.SECONDS))).thenReturn("INVALID_NOT_JSON");

        Optional<ExecutionJob> result = redisExecutionQueue.poll(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
    }

    @Test
    void poll_RedisThrowsException_WrapsInQueueException() {
        when(listOperations.leftPop(eq(QUEUE_KEY), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new RedisConnectionFailureException("Connection lost"));

        assertThrows(QueueException.class, () -> redisExecutionQueue.poll(Duration.ofSeconds(1)));
    }

    @Test
    void size_ReturnsQueueSizeFromRedis() {
        when(listOperations.size(QUEUE_KEY)).thenReturn(5L);

        assertEquals(5L, redisExecutionQueue.size());
    }

    @Test
    void size_RedisError_ReturnsZeroSafely() {
        when(listOperations.size(QUEUE_KEY)).thenThrow(new RedisConnectionFailureException("Connection lost"));

        assertEquals(0L, redisExecutionQueue.size());
    }
}
