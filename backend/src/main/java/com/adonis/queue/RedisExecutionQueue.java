package com.adonis.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis list-based implementation of ExecutionQueue using StringRedisTemplate.
 * Produces jobs with RPUSH and consumes jobs with BLPOP / leftPop.
 */
@Component
@ConditionalOnProperty(name = "adonis.queue.type", havingValue = "redis", matchIfMissing = true)
public class RedisExecutionQueue implements ExecutionQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisExecutionQueue.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String queueKey;

    public RedisExecutionQueue(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${adonis.worker.queue-name:adonis:execution:queue}") String queueKey) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.queueKey = (queueKey != null && !queueKey.isBlank()) ? queueKey : "adonis:execution:queue";
    }

    @Override
    public void enqueue(ExecutionJob job) {
        if (job == null) {
            throw new IllegalArgumentException("ExecutionJob must not be null");
        }
        try {
            String payload = objectMapper.writeValueAsString(job);
            redisTemplate.opsForList().rightPush(queueKey, payload);
            log.info("Enqueued execution job to Redis [{}]: executionId={}, workflowId={}",
                    queueKey, job.executionId(), job.workflowId());
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize execution job: {}", job.executionId(), ex);
            throw new QueueException("Serialization failure for execution job: " + job.executionId(), ex);
        } catch (Exception ex) {
            log.error("Failed to enqueue job to Redis queue [{}]: {}", queueKey, job.executionId(), ex);
            throw new QueueException("Redis enqueue operation failed", ex);
        }
    }

    @Override
    public Optional<ExecutionJob> poll(Duration timeout) {
        try {
            long timeoutSeconds = Math.max(1, timeout != null ? timeout.toSeconds() : 2);
            String payload = redisTemplate.opsForList().leftPop(queueKey, timeoutSeconds, TimeUnit.SECONDS);
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            ExecutionJob job = objectMapper.readValue(payload, ExecutionJob.class);
            return Optional.of(job);
        } catch (JsonProcessingException ex) {
            log.error("Failed to deserialize execution job from Redis queue; discarding corrupt message", ex);
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Error polling Redis queue [{}]", queueKey, ex);
            throw new QueueException("Redis poll operation failed", ex);
        }
    }

    @Override
    public long size() {
        try {
            Long size = redisTemplate.opsForList().size(queueKey);
            return size != null ? size : 0L;
        } catch (Exception ex) {
            log.warn("Failed to check Redis queue size for key [{}]", queueKey, ex);
            return 0L;
        }
    }

    public String getQueueKey() {
        return queueKey;
    }
}
