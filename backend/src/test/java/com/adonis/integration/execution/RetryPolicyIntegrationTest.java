package com.adonis.integration.execution;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.*;
import com.adonis.queue.ExecutionJob;
import com.adonis.queue.QueuedJobMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Retry Policy Integration Tests (Real Execution Engine & MongoDB)")
class RetryPolicyIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Retry policy: HTTP node fails first attempt (500), succeeds on second attempt, records full attempt history in Mongo")
    void retryPolicy_FailureThenSuccess_PersistsAttemptsAndSucceeds() {
        // Enqueue: Attempt 1 returns 500 (retryable), Attempt 2 returns 200
        mockHttpServer.enqueueHttpResponse(500, "{\"error\":\"Server Temporary Glitch\"}");
        mockHttpServer.enqueueHttpResponse(200, "{\"data\":\"recovered\"}");

        // Build HTTP node with retry configuration: maxRetries=2, initialBackoff=50ms
        Map<String, Object> httpData = new LinkedHashMap<>();
        httpData.put("url", mockHttpServer.getHttpEndpointUrl());
        httpData.put("method", "GET");
        httpData.put("retry", Map.of(
                "enabled", true,
                "maxRetries", 2,
                "initialBackoffMs", 50,
                "backoffMultiplier", 1.5,
                "maxBackoffMs", 200
        ));

        WorkflowNode trigger = TestDataFactory.createTriggerNode("trigger_1");
        WorkflowNode http = new WorkflowNode("http_1", "http", httpData);
        WorkflowEdge edge = TestDataFactory.createEdge("trigger_1", "http_1");

        Workflow workflow = TestDataFactory.createWorkflow(null, "user-retry", "Retry Flow", List.of(trigger, http), List.of(edge));
        workflow = workflowRepository.save(workflow);

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), "user-retry", "MANUAL");
        execution = executionRepository.save(execution);

        QueuedJobMessage message = new QueuedJobMessage("msg-retry-test",
                new ExecutionJob(execution.getId(), workflow.getId(), "user-retry", "MANUAL", Instant.now()));

        boolean processed = worker.processJob(message);
        assertTrue(processed);

        // Verify MongoDB execution record
        WorkflowExecution result = executionRepository.findById(execution.getId()).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, result.getStatus());
        assertNull(result.getError());

        NodeExecution httpNodeExec = result.getNodeExecutions().stream()
                .filter(n -> "http_1".equals(n.getNodeId()))
                .findFirst()
                .orElseThrow();

        assertEquals(ExecutionStatus.SUCCESS, httpNodeExec.getStatus());
        assertEquals(1, httpNodeExec.getRetryCount(), "Retry count should be 1 (1 retry after initial attempt)");

        List<NodeExecutionAttempt> attempts = httpNodeExec.getAttempts();
        assertNotNull(attempts);
        assertEquals(2, attempts.size(), "Attempts list must contain 2 recorded attempts");

        // Attempt 1 assertions
        NodeExecutionAttempt attempt1 = attempts.get(0);
        assertEquals(1, attempt1.getAttemptNumber());
        assertEquals(ExecutionStatus.FAILED, attempt1.getStatus());
        assertNotNull(attempt1.getError());
        assertTrue(attempt1.getError().contains("500"));
        assertNotNull(attempt1.getStartedAt());
        assertNotNull(attempt1.getCompletedAt());
        assertNotNull(attempt1.getDurationMs());

        // Attempt 2 assertions
        NodeExecutionAttempt attempt2 = attempts.get(1);
        assertEquals(2, attempt2.getAttemptNumber());
        assertEquals(ExecutionStatus.SUCCESS, attempt2.getStatus());
        assertNull(attempt2.getError());
        assertNotNull(attempt2.getOutput());
        assertNotNull(attempt2.getStartedAt());
        assertNotNull(attempt2.getCompletedAt());
        assertNotNull(attempt2.getDurationMs());
    }

    @Test
    @DisplayName("Retry policy: exhausted retries marks node and workflow FAILED, skips downstream nodes")
    void retryPolicy_ExhaustedRetries_MarksFailedAndSkipsDownstream() {
        // Enqueue: All attempts fail with 503 Service Unavailable
        mockHttpServer.setDefaultHttpResponse(503, "{\"error\":\"Service Unavailable\"}");

        Map<String, Object> httpData = new LinkedHashMap<>();
        httpData.put("url", mockHttpServer.getHttpEndpointUrl());
        httpData.put("method", "GET");
        httpData.put("retry", Map.of(
                "enabled", true,
                "maxRetries", 2, // 1 initial + 2 retries = 3 attempts total
                "initialBackoffMs", 20,
                "backoffMultiplier", 1.0,
                "maxBackoffMs", 100
        ));

        WorkflowNode trigger = TestDataFactory.createTriggerNode("trigger_1");
        WorkflowNode http1 = new WorkflowNode("http_1", "http", httpData);
        WorkflowNode http2 = TestDataFactory.createHttpNode("http_2", mockHttpServer.getHttpEndpointUrl(), "GET", Map.of(), null);

        WorkflowEdge edge1 = TestDataFactory.createEdge("trigger_1", "http_1");
        WorkflowEdge edge2 = TestDataFactory.createEdge("http_1", "http_2");

        Workflow workflow = TestDataFactory.createWorkflow(null, "user-retry", "Exhausted Retry Flow",
                List.of(trigger, http1, http2), List.of(edge1, edge2));
        workflow = workflowRepository.save(workflow);

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), "user-retry", "MANUAL");
        execution = executionRepository.save(execution);

        QueuedJobMessage message = new QueuedJobMessage("msg-exhausted-retry",
                new ExecutionJob(execution.getId(), workflow.getId(), "user-retry", "MANUAL", Instant.now()));

        boolean processed = worker.processJob(message);
        assertTrue(processed);

        WorkflowExecution result = executionRepository.findById(execution.getId()).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertNotNull(result.getError());

        // http_1 must be FAILED with 3 attempts
        NodeExecution node1 = result.getNodeExecutions().stream()
                .filter(n -> "http_1".equals(n.getNodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, node1.getStatus());
        assertEquals(2, node1.getRetryCount());
        assertEquals(3, node1.getAttempts().size());

        // Downstream http_2 must be SKIPPED
        NodeExecution node2 = result.getNodeExecutions().stream()
                .filter(n -> "http_2".equals(n.getNodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.SKIPPED, node2.getStatus());
    }
}
