package com.adonis.integration.queue;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.queue.ExecutionJob;
import com.adonis.queue.QueuedJobMessage;
import com.adonis.queue.RedisExecutionQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Redis Pending Message Recovery Integration Tests (Real Redis Testcontainer)")
class RedisPendingRecoveryIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Pending recovery: claims unacknowledged message from crashed worker, executes, persists, and ACKs")
    void pendingRecovery_WorkerBClaimsStaleMessageFromCrashedWorkerA_AndCompletes() {
        mockHttpServer.setDefaultHttpResponse(200, "{\"recovered\":true}");

        Workflow workflow = TestDataFactory.createHttpWorkflow("user-recovery", "Recovery Flow", mockHttpServer.getHttpEndpointUrl(), "GET");
        workflow = workflowRepository.save(workflow);

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), "user-recovery", "MANUAL");
        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        // 1. Worker A enqueues and reads message (becomes PENDING in Redis PEL)
        RedisExecutionQueue queueWorkerA = new RedisExecutionQueue(
                redisTemplate,
                objectMapper,
                TEST_STREAM_KEY,
                TEST_CONSUMER_GROUP,
                "crashed-worker-A"
        );

        ExecutionJob job = new ExecutionJob(executionId, workflow.getId(), "user-recovery", "MANUAL", Instant.now());
        queueWorkerA.enqueue(job);

        // Worker A polls message but simulates crash before processing/ACKing
        Optional<QueuedJobMessage> msgFromA = queueWorkerA.poll(Duration.ofSeconds(2));
        assertTrue(msgFromA.isPresent());
        assertEquals(executionId, msgFromA.get().job().executionId());

        // Verify message is in PEL under crashed-worker-A
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(TEST_STREAM_KEY, TEST_CONSUMER_GROUP, Range.unbounded(), 10);
        assertNotNull(pending);
        assertEquals(1, pending.size());
        assertEquals("crashed-worker-A", pending.get(0).getConsumerName());

        // 2. Worker B claims the pending unacknowledged message using claimPending with 0 min idle time
        RedisExecutionQueue queueWorkerB = new RedisExecutionQueue(
                redisTemplate,
                objectMapper,
                TEST_STREAM_KEY,
                TEST_CONSUMER_GROUP,
                "surviving-worker-B"
        );

        List<QueuedJobMessage> claimed = queueWorkerB.claimPending(Duration.ZERO, 10);
        assertFalse(claimed.isEmpty(), "Worker B must claim pending message abandoned by Worker A");

        QueuedJobMessage claimedMsg = claimed.get(0);
        assertEquals(executionId, claimedMsg.job().executionId());

        // 3. Worker processes the recovered message
        boolean processed = worker.processJob(claimedMsg);
        assertTrue(processed);

        // 4. Verify MongoDB has reached SUCCESS
        WorkflowExecution completed = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());

        // 5. Verify message is acknowledged and removed from PEL
        PendingMessages pendingAfterRecovery = redisTemplate.opsForStream()
                .pending(TEST_STREAM_KEY, TEST_CONSUMER_GROUP, Range.unbounded(), 10);
        assertTrue(pendingAfterRecovery == null || pendingAfterRecovery.isEmpty(),
                "Recovered message must be XACKed and removed from consumer group PEL");
    }
}
