package com.adonis.integration.queue;

import com.adonis.dto.ExecuteWorkflowResponse;
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
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Redis Streams & Consumer Group Integration Tests (Real Redis Testcontainer)")
class RedisStreamsIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Queue submission: enqueues ExecutionJob via XADD and verifies record in real Redis stream")
    void queueSubmission_EnqueuesJobAndVerifiesRecordInRedisStream() {
        ExecutionJob job = new ExecutionJob("exec-stream-1", "wf-1", "user-1", "MANUAL", Instant.now());

        queue.enqueue(job);

        // Verify record in Redis Stream via low-level opsForStream
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(TEST_STREAM_KEY, Range.unbounded());

        assertNotNull(records);
        assertEquals(1, records.size());

        MapRecord<String, Object, Object> record = records.get(0);
        assertNotNull(record.getId());
        assertTrue(record.getValue().containsKey("payload"));

        String payload = (String) record.getValue().get("payload");
        assertTrue(payload.contains("\"executionId\":\"exec-stream-1\""));
        assertTrue(payload.contains("\"workflowId\":\"wf-1\""));
    }

    @Test
    @DisplayName("Consumer group & worker consumption: polls message, tracks pending entry, executes to SUCCESS, and ACKs")
    void workerConsumption_ConsumesQueuedJob_ExecutesToSuccess_AndAcknowledges() {
        // Setup local mock HTTP response
        mockHttpServer.setDefaultHttpResponse(200, "{\"result\":\"ok\"}");

        // Create workflow in MongoDB
        Workflow workflow = TestDataFactory.createHttpWorkflow("user-test", "HTTP Flow", mockHttpServer.getHttpEndpointUrl(), "GET");
        workflow = workflowRepository.save(workflow);

        // Enqueue execution via executionService
        ExecuteWorkflowResponse response = executionService.enqueueExecution(workflow.getId(), "user-test");
        assertNotNull(response.executionId());

        WorkflowExecution queuedExec = executionRepository.findById(response.executionId()).orElseThrow();
        assertEquals(ExecutionStatus.QUEUED, queuedExec.getStatus());

        // Verify message is in Redis stream
        assertEquals(1, queue.size());

        // Poll from Redis queue
        Optional<QueuedJobMessage> msgOpt = queue.poll(Duration.ofSeconds(3));
        assertTrue(msgOpt.isPresent(), "Worker must poll the enqueued job from Redis stream");
        QueuedJobMessage msg = msgOpt.get();
        assertEquals(response.executionId(), msg.job().executionId());

        // Message is now in Pending Entries List (PEL)
        PendingMessages pendingBeforeAck = redisTemplate.opsForStream()
                .pending(TEST_STREAM_KEY, TEST_CONSUMER_GROUP, Range.unbounded(), 10);
        assertNotNull(pendingBeforeAck);
        assertTrue(pendingBeforeAck.size() >= 1, "Unacknowledged message must be visible in PEL");

        // Process job with worker
        boolean processed = worker.processJob(msg);
        assertTrue(processed, "ExecutionWorker must successfully process the job");

        // Verify execution reached SUCCESS in MongoDB
        WorkflowExecution completed = executionRepository.findById(response.executionId()).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());
        assertNotNull(completed.getCompletedAt());
        assertNotNull(completed.getDurationMs());
        assertFalse(completed.getNodeExecutions().isEmpty());

        // Verify message was acknowledged (removed from PEL)
        PendingMessages pendingAfterAck = redisTemplate.opsForStream()
                .pending(TEST_STREAM_KEY, TEST_CONSUMER_GROUP, Range.unbounded(), 10);
        assertTrue(pendingAfterAck == null || pendingAfterAck.isEmpty(), "Processed message must be acknowledged and removed from PEL");
    }

    @Test
    @DisplayName("Failed execution: worker executes failing workflow, marks FAILED in Mongo, and ACKs Redis message")
    void failedExecution_WorkerPersistsFailure_AndAcknowledgesMessage() {
        // Mock server returns 500 without retries configured on workflow
        mockHttpServer.setDefaultHttpResponse(500, "{\"error\":\"Internal Server Error\"}");

        Workflow workflow = TestDataFactory.createHttpWorkflow("user-test", "Failing Flow", mockHttpServer.getHttpEndpointUrl(), "GET");
        workflow = workflowRepository.save(workflow);

        ExecuteWorkflowResponse response = executionService.enqueueExecution(workflow.getId(), "user-test");
        String executionId = response.executionId();

        Optional<QueuedJobMessage> msgOpt = queue.poll(Duration.ofSeconds(3));
        assertTrue(msgOpt.isPresent());
        QueuedJobMessage msg = msgOpt.get();

        boolean processed = worker.processJob(msg);
        assertTrue(processed);

        // Verify MongoDB contains FAILED status
        WorkflowExecution failed = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, failed.getStatus());
        assertNotNull(failed.getError());
        assertNotNull(failed.getCompletedAt());

        // Message must still be acknowledged after terminal persistence to prevent infinite poison loop
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(TEST_STREAM_KEY, TEST_CONSUMER_GROUP, Range.unbounded(), 10);
        assertTrue(pending == null || pending.isEmpty(), "Failed execution must be acknowledged after terminal persistence");
    }
}
