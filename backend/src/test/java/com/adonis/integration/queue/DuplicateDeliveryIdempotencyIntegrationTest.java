package com.adonis.integration.queue;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.queue.ExecutionJob;
import com.adonis.queue.ExecutionWorker;
import com.adonis.queue.QueuedJobMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Duplicate Delivery & Idempotency Integration Tests (Real MongoDB)")
class DuplicateDeliveryIdempotencyIntegrationTest extends AdonisIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.adonis.execution.WorkflowExecutionValidator validator;

    @Test
    @DisplayName("Duplicate delivery: two concurrent workers receive same message, only one atomically claims and executes")
    void duplicateDelivery_TwoWorkersReceiveSameMessage_OnlyOneClaimsAndExecutes() throws Exception {
        mockHttpServer.setDefaultHttpResponse(200, "{\"success\":true}");

        Workflow workflow = TestDataFactory.createHttpWorkflow("user-dup", "Dup Workflow", mockHttpServer.getHttpEndpointUrl(), "GET");
        workflow = workflowRepository.save(workflow);

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), "user-dup", "MANUAL");
        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        ExecutionWorker worker1 = new ExecutionWorker(queue, workflowRepository, executionRepository,
                validator, engine, mongoTemplate,
                false, 500L, 60000L, 30000L, 10000L, "worker-1");

        ExecutionWorker worker2 = new ExecutionWorker(queue, workflowRepository, executionRepository,
                validator, engine, mongoTemplate,
                false, 500L, 60000L, 30000L, 10000L, "worker-2");

        QueuedJobMessage message = new QueuedJobMessage("msg-dup-delivery",
                new ExecutionJob(executionId, workflow.getId(), "user-dup", "MANUAL", Instant.now()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successClaims = new AtomicInteger(0);

        Future<Boolean> f1 = pool.submit(() -> {
            startLatch.await();
            boolean res = worker1.processJob(message);
            if (res) successClaims.incrementAndGet();
            return res;
        });

        Future<Boolean> f2 = pool.submit(() -> {
            startLatch.await();
            boolean res = worker2.processJob(message);
            if (res) successClaims.incrementAndGet();
            return res;
        });

        startLatch.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactly ONE worker must have successfully claimed the execution
        assertEquals(1, successClaims.get(), "Atomic findAndModify must allow exactly one worker to claim QUEUED execution");

        WorkflowExecution finalExecution = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, finalExecution.getStatus());
        assertNotNull(finalExecution.getWorkerId());
        assertTrue(finalExecution.getWorkerId().equals("worker-1") || finalExecution.getWorkerId().equals("worker-2"));
    }

    @Test
    @DisplayName("Duplicate delivery on terminal status: acknowledges duplicate message without re-executing")
    void duplicateDelivery_OnTerminalSuccess_AcknowledgesWithoutReExecuting() {
        Workflow workflow = TestDataFactory.createHttpWorkflow("user-dup", "Terminal Workflow", "http://localhost:8080", "GET");
        workflow = workflowRepository.save(workflow);

        Instant completedAt = Instant.now();
        WorkflowExecution terminal = new WorkflowExecution("exec-already-done", workflow.getId(), "user-dup",
                ExecutionStatus.SUCCESS, "MANUAL", completedAt.minusMillis(500), completedAt, 500L, java.util.List.of(), null);
        executionRepository.save(terminal);

        QueuedJobMessage duplicateMsg = new QueuedJobMessage("msg-dup-terminal",
                new ExecutionJob("exec-already-done", workflow.getId(), "user-dup", "MANUAL", Instant.now()));

        boolean processed = worker.processJob(duplicateMsg);
        assertFalse(processed, "Duplicate delivery of already SUCCESS execution must be skipped");

        WorkflowExecution unchanged = executionRepository.findById("exec-already-done").orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, unchanged.getStatus());
        assertEquals(completedAt.toEpochMilli(), unchanged.getCompletedAt().toEpochMilli());
    }
}
