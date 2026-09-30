package com.adonis.execution;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.model.*;
import com.adonis.queue.ExecutionWorker;
import com.adonis.queue.InMemoryExecutionQueue;
import com.adonis.queue.QueuedJobMessage;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TriggerPipelineIntegrationTest {

    private static MongoServer server;
    private static InetSocketAddress serverAddress;

    @BeforeAll
    static void setUpServer() {
        server = new MongoServer(new MemoryBackend());
        serverAddress = server.bind();
    }

    @AfterAll
    static void tearDownServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @DynamicPropertySource
    static void setMongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                () -> "mongodb://" + serverAddress.getHostName() + ":" + serverAddress.getPort() + "/adonis-pipeline-test");
    }

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowExecutionValidator validator;

    @Autowired
    private WorkflowExecutionEngine engine;

    @Autowired
    private MongoTemplate mongoTemplate;

    private InMemoryExecutionQueue executionQueue;
    private WorkflowExecutionService executionService;
    private ExecutionWorker worker;

    private Workflow scheduleWorkflow;
    private Workflow webhookWorkflow;

    @BeforeEach
    void setUp() {
        executionRepository.deleteAll();
        workflowRepository.deleteAll();

        executionQueue = new InMemoryExecutionQueue();
        executionService = new WorkflowExecutionService(
                workflowRepository,
                validator,
                engine,
                executionRepository,
                executionQueue
        );

        worker = new ExecutionWorker(
                executionQueue,
                workflowRepository,
                executionRepository,
                validator,
                engine,
                mongoTemplate,
                true,
                1000L,
                60000L,
                60000L,
                "test-pipeline-worker"
        );

        WorkflowNode triggerNode = new WorkflowNode("node-trig", "trigger", Map.of("label", "Pipeline Trigger"));
        WorkflowNode actionNode = new WorkflowNode("node-action", "generic", Map.of("label", "Action Step"));
        WorkflowEdge edge = new WorkflowEdge("edge-1", "node-trig", "node-action");

        WorkflowTriggerConfig schedConfig = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        scheduleWorkflow = Workflow.create(
                "user-pipeline",
                "Scheduled Pipeline Workflow",
                "Pipeline Test",
                WorkflowStatus.ACTIVE,
                List.of(triggerNode, actionNode),
                List.of(edge),
                WorkflowTriggerType.SCHEDULE,
                schedConfig
        );
        scheduleWorkflow = workflowRepository.save(scheduleWorkflow);

        WorkflowTriggerConfig whConfig = new WorkflowTriggerConfig(null, null, "wh-pipe-12345678", null, false);
        webhookWorkflow = Workflow.create(
                "user-pipeline",
                "Webhook Pipeline Workflow",
                "Pipeline Test",
                WorkflowStatus.ACTIVE,
                List.of(triggerNode, actionNode),
                List.of(edge),
                WorkflowTriggerType.WEBHOOK,
                whConfig
        );
        webhookWorkflow = workflowRepository.save(webhookWorkflow);
    }

    @Test
    void schedulePipeline_ScheduleToExecutionServiceToQueueToWorkerToEngine_Succeeds() {
        Instant fireTime = Instant.now();
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey(scheduleWorkflow.getId(), fireTime);

        // 1. Enqueue scheduled execution
        ExecuteWorkflowResponse enqueueResponse = executionService.enqueueScheduledExecution(
                scheduleWorkflow,
                occurrenceKey,
                fireTime
        );

        assertNotNull(enqueueResponse.executionId());
        assertEquals(ExecutionStatus.QUEUED, enqueueResponse.status());
        assertEquals(1, executionQueue.size());

        // 2. Execution worker consumes job from queue and processes through engine
        Optional<QueuedJobMessage> messageOpt = executionQueue.poll(Duration.ofMillis(500));
        assertTrue(messageOpt.isPresent());

        boolean processed = worker.processJob(messageOpt.get());
        assertTrue(processed);

        // 3. Verify terminal execution record in MongoDB
        WorkflowExecution terminalExec = executionRepository.findById(enqueueResponse.executionId()).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, terminalExec.getStatus());
        assertEquals("SCHEDULE", terminalExec.getTriggerType());
        assertEquals(occurrenceKey, terminalExec.getScheduledOccurrence());
        assertNotNull(terminalExec.getCompletedAt());
        assertTrue(terminalExec.getNodeExecutions().size() >= 2);

        // Verify trigger node executed successfully
        NodeExecution triggerExec = terminalExec.getNodeExecutions().get(0);
        assertEquals("node-trig", triggerExec.getNodeId());
        assertEquals(ExecutionStatus.SUCCESS, triggerExec.getStatus());
        assertEquals("schedule", triggerExec.getOutput().get("trigger"));
        assertEquals("SCHEDULE", triggerExec.getOutput().get("type"));
    }

    @Test
    void webhookPipeline_WebhookToExecutionServiceToQueueToWorkerToEngine_Succeeds() {
        Map<String, Object> webhookPayload = Map.of(
                "event", "customer.created",
                "customerId", "cust_12345",
                "email", "cust@example.com"
        );

        // 1. Enqueue webhook execution with idempotency key
        ExecuteWorkflowResponse enqueueResponse = executionService.enqueueWebhookExecution(
                webhookWorkflow,
                "idem-pipeline-001",
                webhookPayload
        );

        assertNotNull(enqueueResponse.executionId());
        assertEquals(ExecutionStatus.QUEUED, enqueueResponse.status());
        assertEquals(1, executionQueue.size());

        // 2. Execution worker consumes job from queue and processes through engine
        Optional<QueuedJobMessage> messageOpt = executionQueue.poll(Duration.ofMillis(500));
        assertTrue(messageOpt.isPresent());

        boolean processed = worker.processJob(messageOpt.get());
        assertTrue(processed);

        // 3. Verify terminal execution record in MongoDB
        WorkflowExecution terminalExec = executionRepository.findById(enqueueResponse.executionId()).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, terminalExec.getStatus());
        assertEquals("WEBHOOK", terminalExec.getTriggerType());
        assertEquals("idem-pipeline-001", terminalExec.getIdempotencyKey());
        assertNotNull(terminalExec.getCompletedAt());

        // Verify trigger node received webhook payload
        NodeExecution triggerExec = terminalExec.getNodeExecutions().get(0);
        assertEquals("node-trig", triggerExec.getNodeId());
        assertEquals(ExecutionStatus.SUCCESS, triggerExec.getStatus());
        assertEquals("webhook", triggerExec.getOutput().get("trigger"));
        assertEquals("WEBHOOK", triggerExec.getOutput().get("type"));

        // Downstream node executed successfully
        NodeExecution actionExec = terminalExec.getNodeExecutions().get(1);
        assertEquals("node-action", actionExec.getNodeId());
        assertEquals(ExecutionStatus.SUCCESS, actionExec.getStatus());
    }

    @Test
    void webhookPipeline_DuplicateIdempotencyKey_DoesNotEnqueueDuplicateJob() {
        Map<String, Object> payload = Map.of("event", "test.event");

        // First delivery
        ExecuteWorkflowResponse first = executionService.enqueueWebhookExecution(
                webhookWorkflow,
                "idem-dup-test",
                payload
        );
        assertEquals(1, executionQueue.size());

        // Duplicate delivery with same key
        ExecuteWorkflowResponse second = executionService.enqueueWebhookExecution(
                webhookWorkflow,
                "idem-dup-test",
                payload
        );

        // Returns same execution ID without queueing a second job
        assertEquals(first.executionId(), second.executionId());
        assertEquals(1, executionQueue.size());
    }

    // ==========================================
    // Phase 8.1 FIX #6 — Webhook Idempotency + Queue Failure Recovery
    // ==========================================

    @Test
    void webhookPipeline_QueueFailureThenRetryWithSameIdempotencyKey_RecoversSuccessfully() {
        // Create an ExecutionQueue that fails on first attempt to simulate transient Redis outage
        boolean[] failEnqueue = new boolean[]{true};
        InMemoryExecutionQueue faultyQueue = new InMemoryExecutionQueue() {
            @Override
            public void enqueue(com.adonis.queue.ExecutionJob job) {
                if (failEnqueue[0]) {
                    throw new RuntimeException("Redis connection refused: transient network error");
                }
                executionQueue.enqueue(job);
            }
        };

        WorkflowExecutionService serviceWithFaultyQueue = new WorkflowExecutionService(
                workflowRepository,
                validator,
                engine,
                executionRepository,
                faultyQueue
        );

        String idempotencyKey = "transient-fail-key-001";
        Map<String, Object> payload = Map.of("event", "payment.created", "amount", 500);

        // 1. First attempt fails due to transient queue outage
        assertThrows(com.adonis.queue.QueueSubmissionException.class, () ->
                serviceWithFaultyQueue.enqueueWebhookExecution(webhookWorkflow, idempotencyKey, payload)
        );

        // Verify execution was created in MongoDB and marked FAILED with no worker started
        WorkflowExecution failedExec = executionRepository
                .findByWorkflowIdAndIdempotencyKey(webhookWorkflow.getId(), idempotencyKey)
                .orElseThrow();
        assertEquals(ExecutionStatus.FAILED, failedExec.getStatus());
        assertNull(failedExec.getStartedAt());
        assertTrue(failedExec.getError().contains("Failed to queue"));
        assertEquals(0, executionQueue.size());

        // 2. Redis recovers (queue now succeeds)
        failEnqueue[0] = false;

        // 3. Client retries with the SAME Idempotency-Key
        ExecuteWorkflowResponse retryResponse = serviceWithFaultyQueue.enqueueWebhookExecution(
                webhookWorkflow,
                idempotencyKey,
                payload
        );

        // Verify it was successfully requeued with the same executionId
        assertNotNull(retryResponse.executionId());
        assertEquals(failedExec.getId(), retryResponse.executionId(), "Must reuse existing execution ID");
        assertEquals(ExecutionStatus.QUEUED, retryResponse.status());
        assertEquals(1, executionQueue.size(), "Job must be placed in queue upon recovery");

        // Verify execution record in DB is now QUEUED and error cleared
        WorkflowExecution requeuedExec = executionRepository.findById(failedExec.getId()).orElseThrow();
        assertEquals(ExecutionStatus.QUEUED, requeuedExec.getStatus());
        assertNull(requeuedExec.getError());

        // 4. Execution worker processes the retried execution successfully
        Optional<QueuedJobMessage> messageOpt = executionQueue.poll(Duration.ofMillis(500));
        assertTrue(messageOpt.isPresent());
        boolean processed = worker.processJob(messageOpt.get());
        assertTrue(processed);

        // 5. Final state in MongoDB is SUCCESS
        WorkflowExecution completedExec = executionRepository.findById(failedExec.getId()).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, completedExec.getStatus());
        assertNotNull(completedExec.getCompletedAt());

        // 6. Verify only ONE execution exists for (workflowId, idempotencyKey)
        List<WorkflowExecution> allForWorkflow = executionRepository.findAll();
        long matchingCount = allForWorkflow.stream()
                .filter(e -> webhookWorkflow.getId().equals(e.getWorkflowId()) && idempotencyKey.equals(e.getIdempotencyKey()))
                .count();
        assertEquals(1, matchingCount, "Must never produce more than one execution for the same (workflowId, idempotencyKey)");
    }
}
