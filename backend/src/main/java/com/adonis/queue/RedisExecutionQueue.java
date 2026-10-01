package com.adonis.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Reliable Redis Streams-based implementation of ExecutionQueue.
 * Uses Redis Streams (XADD) for publishing, Consumer Groups (XREADGROUP) for at-least-once delivery,
 * explicit message acknowledgement (XACK) upon successful persistence, and pending entry inspection
 * (XPENDING / XCLAIM) to recover messages from crashed workers.
 */
@Component
@ConditionalOnProperty(name = "adonis.queue.type", havingValue = "redis", matchIfMissing = true)
public class RedisExecutionQueue implements ExecutionQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisExecutionQueue.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String streamKey;
    private final String consumerGroup;
    private final String consumerName;
    private final String dlqStreamKey;
    private final AtomicBoolean groupInitialized = new AtomicBoolean(false);

    public RedisExecutionQueue(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${adonis.worker.stream-name:${adonis.worker.queue-name:adonis:execution:stream}}") String streamKey,
            @Value("${adonis.worker.consumer-group:adonis-workers}") String consumerGroup,
            @Value("${adonis.worker.consumer-name:}") String consumerName) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.streamKey = (streamKey != null && !streamKey.isBlank()) ? streamKey : "adonis:execution:stream";
        this.consumerGroup = (consumerGroup != null && !consumerGroup.isBlank()) ? consumerGroup : "adonis-workers";
        this.consumerName = (consumerName != null && !consumerName.isBlank())
                ? consumerName
                : "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.dlqStreamKey = this.streamKey + ":dlq";
    }

    @Override
    public void enqueue(ExecutionJob job) {
        if (job == null) {
            throw new IllegalArgumentException("ExecutionJob must not be null");
        }
        try {
            ensureGroupExists();
            String payload = objectMapper.writeValueAsString(job);
            Map<String, String> fields = Map.of("payload", payload);
            RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);

            log.info("Enqueued execution job to Redis Stream [{}]: executionId={}, workflowId={}, recordId={}",
                    streamKey, job.executionId(), job.workflowId(), recordId != null ? recordId.getValue() : "unknown");
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize execution job: {}", job.executionId(), ex);
            throw new QueueException("Serialization failure for execution job: " + job.executionId(), ex);
        } catch (Exception ex) {
            log.error("Failed to enqueue job to Redis stream [{}]: {}", streamKey, job.executionId(), ex);
            throw new QueueException("Redis enqueue operation failed", ex);
        }
    }

    @Override
    public Optional<QueuedJobMessage> poll(Duration timeout) {
        try {
            ensureGroupExists();
            Consumer consumer = Consumer.from(consumerGroup, consumerName);
            StreamOffset<String> streamOffset = StreamOffset.create(streamKey, ReadOffset.lastConsumed());
            Duration blockDuration = timeout != null ? timeout : Duration.ofSeconds(2);
            StreamReadOptions readOptions = StreamReadOptions.empty().count(1).block(blockDuration);

            List<MapRecord<String, String, String>> records = redisTemplate.<String, String>opsForStream()
                    .read(consumer, readOptions, streamOffset);

            if (records == null || records.isEmpty()) {
                return Optional.empty();
            }

            MapRecord<String, String, String> record = records.get(0);
            String recordId = record.getId().getValue();
            String payload = record.getValue().get("payload");

            if (payload == null || payload.isBlank()) {
                quarantineMalformedMessage(record.getId(), record.getValue(), "Missing payload field in stream record");
                return Optional.empty();
            }

            try {
                ExecutionJob job = objectMapper.readValue(payload, ExecutionJob.class);
                return Optional.of(new QueuedJobMessage(recordId, job, 1));
            } catch (JsonProcessingException ex) {
                quarantineMalformedMessage(record.getId(), record.getValue(), "Corrupt JSON: " + ex.getMessage());
                return Optional.empty();
            }
        } catch (Exception ex) {
            if (isNoGroupException(ex)) {
                log.info("Consumer group missing for [{}], reinitializing and retrying read once", streamKey);
                groupInitialized.set(false);
                ensureGroupExists();
                try {
                    Consumer consumer = Consumer.from(consumerGroup, consumerName);
                    StreamOffset<String> streamOffset = StreamOffset.create(streamKey, ReadOffset.lastConsumed());
                    Duration blockDuration = timeout != null ? timeout : Duration.ofSeconds(2);
                    StreamReadOptions readOptions = StreamReadOptions.empty().count(1).block(blockDuration);
                    List<MapRecord<String, String, String>> records = redisTemplate.<String, String>opsForStream()
                            .read(consumer, readOptions, streamOffset);
                    if (records != null && !records.isEmpty()) {
                        MapRecord<String, String, String> record = records.get(0);
                        String recordId = record.getId().getValue();
                        String payload = record.getValue().get("payload");
                        if (payload != null && !payload.isBlank()) {
                            try {
                                ExecutionJob job = objectMapper.readValue(payload, ExecutionJob.class);
                                return Optional.of(new QueuedJobMessage(recordId, job, 1));
                            } catch (JsonProcessingException ignored) {
                            }
                        }
                    }
                } catch (Exception retryEx) {
                    log.warn("Retry read after NOGROUP recovery failed on stream [{}]: {}", streamKey, retryEx.getMessage());
                }
                return Optional.empty();
            }
            log.error("Error polling Redis stream [{}] with consumer [{}]", streamKey, consumerName, ex);
            throw new QueueException("Redis poll operation failed", ex);
        }
    }

    private static final Pattern STREAM_ID_PATTERN = Pattern.compile("^\\d+-\\d+$");

    @Override
    public void acknowledge(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, RecordId.of(messageId));
            log.debug("Acknowledged message [{}] in consumer group [{}] on stream [{}]",
                    messageId, consumerGroup, streamKey);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
                msg += " " + ex.getCause().getMessage();
            }
            if (msg.contains("Invalid stream ID")) {
                log.warn("Ignored XACK for invalid stream ID [{}]: {}", messageId, msg);
                return;
            }
            log.error("Failed to acknowledge message [{}] on stream [{}]", messageId, streamKey, ex);
            throw new QueueException("Redis acknowledge operation failed", ex);
        }
    }

    @Override
    public List<QueuedJobMessage> claimPending(Duration minIdleTime, int count) {
        if (minIdleTime == null) {
            minIdleTime = Duration.ofMinutes(1);
        }
        List<QueuedJobMessage> claimedMessages = new ArrayList<>();
        try {
            ensureGroupExists();
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, consumerGroup, Range.unbounded(), (long) count);

            if (pending == null || pending.isEmpty()) {
                return Collections.emptyList();
            }

            List<RecordId> staleRecordIds = new ArrayList<>();
            Map<String, Long> deliveryCountMap = new HashMap<>();

            for (PendingMessage pm : pending) {
                Duration elapsed = pm.getElapsedTimeSinceLastDelivery();
                if (elapsed != null && elapsed.compareTo(minIdleTime) >= 0) {
                    staleRecordIds.add(pm.getId());
                    deliveryCountMap.put(pm.getId().getValue(), pm.getTotalDeliveryCount());
                }
            }

            if (staleRecordIds.isEmpty()) {
                return Collections.emptyList();
            }

            RecordId[] idsArray = staleRecordIds.toArray(new RecordId[0]);
            List<MapRecord<String, String, String>> claimedRecords = redisTemplate.<String, String>opsForStream()
                    .claim(streamKey, consumerGroup, consumerName, minIdleTime, idsArray);

            if (claimedRecords == null || claimedRecords.isEmpty()) {
                return Collections.emptyList();
            }

            for (MapRecord<String, String, String> record : claimedRecords) {
                String recordId = record.getId().getValue();
                String payload = record.getValue().get("payload");

                if (payload == null || payload.isBlank()) {
                    quarantineMalformedMessage(record.getId(), record.getValue(), "Missing payload in claimed record");
                    continue;
                }

                try {
                    ExecutionJob job = objectMapper.readValue(payload, ExecutionJob.class);
                    long deliveryCount = deliveryCountMap.getOrDefault(recordId, 1L);
                    claimedMessages.add(new QueuedJobMessage(recordId, job, (int) deliveryCount));
                } catch (JsonProcessingException ex) {
                    quarantineMalformedMessage(record.getId(), record.getValue(), "Corrupt JSON in claimed record: " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            if (isNoGroupException(ex)) {
                groupInitialized.set(false);
                ensureGroupExists();
                return Collections.emptyList();
            }
            log.error("Failed to query or claim pending messages from Redis stream [{}]", streamKey, ex);
        }
        return claimedMessages;
    }

    @Override
    public long size() {
        try {
            Long size = redisTemplate.opsForStream().size(streamKey);
            return size != null ? size : 0L;
        } catch (Exception ex) {
            log.warn("Failed to check Redis stream size for key [{}]", streamKey, ex);
            return 0L;
        }
    }

    public void ensureGroupExists() {
        if (groupInitialized.get()) {
            return;
        }
        synchronized (this) {
            if (groupInitialized.get()) {
                return;
            }
            try {
                redisTemplate.execute((RedisConnection connection) -> {
                    try {
                        connection.streamCommands().xGroupCreate(
                                streamKey.getBytes(StandardCharsets.UTF_8),
                                consumerGroup,
                                ReadOffset.from("0"),
                                true // makeStream = true (creates stream if not existing)
                        );
                        log.info("Initialized consumer group [{}] on stream [{}]", consumerGroup, streamKey);
                    } catch (Exception ex) {
                        String msg = ex.getMessage();
                        if (msg != null && (msg.contains("BUSYGROUP") || msg.contains("already exists"))) {
                            log.debug("Consumer group [{}] already exists for stream [{}]", consumerGroup, streamKey);
                        } else {
                            log.warn("Notice during stream group initialization for [{}]: {}", streamKey, msg);
                        }
                    }
                    return null;
                });
                groupInitialized.set(true);
            } catch (Exception ex) {
                log.warn("Could not ensure group [{}] exists on stream [{}]: {}", consumerGroup, streamKey, ex.getMessage());
            }
        }
    }

    private void quarantineMalformedMessage(RecordId recordId, Map<String, String> value, String reason) {
        try {
            log.warn("Quarantining malformed Redis stream message [{}] to [{}]: {}",
                    recordId.getValue(), dlqStreamKey, reason);
            Map<String, String> dlqEntry = new HashMap<>();
            dlqEntry.put("originalMessageId", recordId.getValue());
            dlqEntry.put("error", reason);
            dlqEntry.put("quarantinedAt", Instant.now().toString());
            redisTemplate.opsForStream().add(dlqStreamKey, dlqEntry);
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, recordId);
        } catch (Exception ex) {
            log.error("Failed to quarantine malformed message [{}]", recordId.getValue(), ex);
        }
    }

    public void resetGroupInitialization() {
        groupInitialized.set(false);
    }

    private boolean isNoGroupException(Throwable t) {
        while (t != null) {
            String m = t.getMessage();
            if (m != null) {
                String upper = m.toUpperCase(Locale.ROOT);
                if (upper.contains("NOGROUP") || upper.contains("NO SUCH KEY")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    public String getStreamKey() {
        return streamKey;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public String getDlqStreamKey() {
        return dlqStreamKey;
    }
}
