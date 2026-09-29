package com.adonis.execution;

import com.adonis.model.NodeExecution;
import com.adonis.model.NodeExecutionAttempt;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WorkflowExecutionRetryTest {

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    private FailureClassifier failureClassifier;
    private RetryPolicy testRetryPolicy;
    private WorkflowExecutionEngine testEngine;

    @BeforeEach
    void setUp() {
        failureClassifier = new FailureClassifier();
        // Use noOp delay strategy so tests execute instantaneously without sleeping
        testRetryPolicy = new RetryPolicy(failureClassifier, RetryDelayStrategy.noOp());
    }

    @Test
    void workflow_NodeFailsOnceThenSucceeds_DownstreamReceivesSuccessfulOutput() {
        AtomicInteger attemptCounter = new AtomicInteger();

        NodeExecutor mockFlakyExecutor = new NodeExecutor() {
            @Override
            public boolean supports(String nodeType) {
                return "flakyHttp".equals(nodeType);
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
                int attempt = attemptCounter.incrementAndGet();
                Instant now = Instant.now();
                if (attempt == 1) {
                    return NodeExecutionResult.failure(node.getId(), node.getType(), now, now,
                            input, Map.of("statusCode", 503), "HTTP 503 SERVICE_UNAVAILABLE");
                }
                return NodeExecutionResult.success(node.getId(), node.getType(), now, now,
                        input, Map.of("data", "fetched-successfully", "code", 200));
            }
        };

        NodeExecutor mockDownstreamExecutor = new NodeExecutor() {
            @Override
            public boolean supports(String nodeType) {
                return "downstreamTransform".equals(nodeType);
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
                Instant now = Instant.now();
                return NodeExecutionResult.success(node.getId(), node.getType(), now, now,
                        input, Map.of("received", input.get("data"), "transformed", true));
            }
        };

        TriggerNodeExecutor triggerExecutor = new TriggerNodeExecutor();
        testEngine = new WorkflowExecutionEngine(List.of(triggerExecutor, mockFlakyExecutor, mockDownstreamExecutor), testRetryPolicy);

        Workflow workflow = new Workflow();
        workflow.setId("wf-retry-success");
        workflow.setUserId("user-1");

        WorkflowNode triggerNode = new WorkflowNode("trigger-1", "trigger", Map.of("triggerType", "manual"));
        WorkflowNode flakyNode = new WorkflowNode("http-flaky-1", "flakyHttp", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 3, "initialBackoffMs", 500L)
        ));
        WorkflowNode downstreamNode = new WorkflowNode("downstream-1", "downstreamTransform", Map.of());

        workflow.setNodes(List.of(triggerNode, flakyNode, downstreamNode));
        workflow.setEdges(List.of(
                new WorkflowEdge("edge-1", "trigger-1", "http-flaky-1"),
                new WorkflowEdge("edge-2", "http-flaky-1", "downstream-1")
        ));

        WorkflowExecutionResult result = testEngine.execute(workflow, List.of(triggerNode, flakyNode, downstreamNode), "user-1");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(3, result.nodes().size());

        // Check flaky node execution result
        NodeExecutionResult flakyResult = result.nodes().get(1);
        assertEquals("http-flaky-1", flakyResult.nodeId());
        assertEquals(ExecutionStatus.SUCCESS, flakyResult.status());
        assertEquals(1, flakyResult.retryCount(), "1 retry occurred");
        assertEquals(2, flakyResult.attempts().size(), "2 attempts recorded");

        NodeExecutionAttempt attempt1 = flakyResult.attempts().get(0);
        assertEquals(1, attempt1.getAttemptNumber());
        assertEquals(ExecutionStatus.FAILED, attempt1.getStatus());
        assertTrue(attempt1.getError().contains("503"));

        NodeExecutionAttempt attempt2 = flakyResult.attempts().get(1);
        assertEquals(2, attempt2.getAttemptNumber());
        assertEquals(ExecutionStatus.SUCCESS, attempt2.getStatus());
        assertEquals("fetched-successfully", attempt2.getOutput().get("data"));

        // Check downstream node received flaky node's attempt 2 output
        NodeExecutionResult downstreamResult = result.nodes().get(2);
        assertEquals("downstream-1", downstreamResult.nodeId());
        assertEquals(ExecutionStatus.SUCCESS, downstreamResult.status());
        assertEquals("fetched-successfully", downstreamResult.output().get("received"));
    }

    @Test
    void workflow_ExhaustedRetries_FailsWorkflowAndSkipsDownstream() {
        AtomicInteger attemptCounter = new AtomicInteger();

        NodeExecutor mockAlwaysFailingExecutor = new NodeExecutor() {
            @Override
            public boolean supports(String nodeType) {
                return "alwaysFails".equals(nodeType);
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
                attemptCounter.incrementAndGet();
                Instant now = Instant.now();
                return NodeExecutionResult.failure(node.getId(), node.getType(), now, now,
                        input, Map.of("statusCode", 504), "HTTP 504 GATEWAY_TIMEOUT");
            }
        };

        NodeExecutor mockDownstreamExecutor = new NodeExecutor() {
            @Override
            public boolean supports(String nodeType) {
                return "downstreamTransform".equals(nodeType);
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
                return NodeExecutionResult.success(node.getId(), node.getType(), Instant.now(), Instant.now(), input, Map.of());
            }
        };

        TriggerNodeExecutor triggerExecutor = new TriggerNodeExecutor();
        testEngine = new WorkflowExecutionEngine(List.of(triggerExecutor, mockAlwaysFailingExecutor, mockDownstreamExecutor), testRetryPolicy);

        Workflow workflow = new Workflow();
        workflow.setId("wf-retry-exhausted");
        workflow.setUserId("user-1");

        WorkflowNode triggerNode = new WorkflowNode("trigger-1", "trigger", Map.of("triggerType", "manual"));
        WorkflowNode failingNode = new WorkflowNode("fails-1", "alwaysFails", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 2, "initialBackoffMs", 500L)
        ));
        WorkflowNode downstreamNode = new WorkflowNode("downstream-1", "downstreamTransform", Map.of());

        workflow.setNodes(List.of(triggerNode, failingNode, downstreamNode));
        workflow.setEdges(List.of(
                new WorkflowEdge("edge-1", "trigger-1", "fails-1"),
                new WorkflowEdge("edge-2", "fails-1", "downstream-1")
        ));

        // Topological order includes downstream, but fail-fast will stop at fails-1
        WorkflowExecutionResult result = testEngine.execute(workflow, List.of(triggerNode, failingNode, downstreamNode), "user-1");

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(3, attemptCounter.get(), "1 initial attempt + 2 retries = 3 attempts total");

        // Engine returns executed nodes (trigger + failingNode)
        assertEquals(2, result.nodes().size());
        NodeExecutionResult nodeFailResult = result.nodes().get(1);
        assertEquals(ExecutionStatus.FAILED, nodeFailResult.status());
        assertEquals(2, nodeFailResult.retryCount());
        assertEquals(3, nodeFailResult.attempts().size());
        assertTrue(nodeFailResult.error().contains("Node failed after 3 attempts"));
    }

    @Test
    void workflow_NonRetryableError_FailsImmediatelyWithoutRetrying() {
        AtomicInteger attemptCounter = new AtomicInteger();

        NodeExecutor mockAuthFailingExecutor = new NodeExecutor() {
            @Override
            public boolean supports(String nodeType) {
                return "authFail".equals(nodeType);
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
                attemptCounter.incrementAndGet();
                Instant now = Instant.now();
                return NodeExecutionResult.failure(node.getId(), node.getType(), now, now,
                        input, Map.of("statusCode", 401), "HTTP 401 UNAUTHORIZED");
            }
        };

        TriggerNodeExecutor triggerExecutor = new TriggerNodeExecutor();
        testEngine = new WorkflowExecutionEngine(List.of(triggerExecutor, mockAuthFailingExecutor), testRetryPolicy);

        Workflow workflow = new Workflow();
        workflow.setId("wf-non-retryable");
        workflow.setUserId("user-1");

        WorkflowNode triggerNode = new WorkflowNode("trigger-1", "trigger", Map.of("triggerType", "manual"));
        WorkflowNode authNode = new WorkflowNode("auth-node", "authFail", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 3)
        ));

        workflow.setNodes(List.of(triggerNode, authNode));
        workflow.setEdges(List.of(new WorkflowEdge("edge-1", "trigger-1", "auth-node")));

        WorkflowExecutionResult result = testEngine.execute(workflow, List.of(triggerNode, authNode), "user-1");

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(1, attemptCounter.get(), "HTTP 401 is non-retryable; should execute exactly once");

        NodeExecutionResult nodeResult = result.nodes().get(1);
        assertEquals(0, nodeResult.retryCount());
        assertEquals(1, nodeResult.attempts().size());
        assertFalse(nodeResult.error().contains("Node failed after"));
        assertTrue(nodeResult.error().contains("401"));
    }

    @Test
    void workflow_SecretRedaction_RedactsSensitiveDataInAttemptsAndErrors() {
        NodeExecutor mockSensitiveExecutor = new NodeExecutor() {
            @Override
            public boolean supports(String nodeType) {
                return "sensitiveNode".equals(nodeType);
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
                Instant now = Instant.now();
                return NodeExecutionResult.failure(node.getId(), node.getType(), now, now,
                        Map.of("apiKey", "super-secret-key-12345", "password", "myPassword"),
                        Map.of("Authorization", "Bearer sensitive-token-xyz"),
                        "Failed while authenticating with token Bearer secret-raw-token",
                        0,
                        List.of()
                );
            }
        };

        TriggerNodeExecutor triggerExecutor = new TriggerNodeExecutor();
        testEngine = new WorkflowExecutionEngine(List.of(triggerExecutor, mockSensitiveExecutor), testRetryPolicy);

        Workflow workflow = new Workflow();
        workflow.setId("wf-secrets");
        workflow.setUserId("user-1");

        WorkflowNode triggerNode = new WorkflowNode("trigger-1", "trigger", Map.of());
        WorkflowNode sensitiveNode = new WorkflowNode("sensitive-1", "sensitiveNode", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 1)
        ));

        workflow.setNodes(List.of(triggerNode, sensitiveNode));
        workflow.setEdges(List.of(new WorkflowEdge("edge-1", "trigger-1", "sensitive-1")));

        WorkflowExecutionResult result = testEngine.execute(workflow, List.of(triggerNode, sensitiveNode), "user-1");

        NodeExecutionResult sensitiveResult = result.nodes().get(1);
        NodeExecutionAttempt attempt = sensitiveResult.attempts().get(0);

        // Verify inputs redacted
        assertEquals("[REDACTED]", attempt.getInput().get("apiKey"));
        assertEquals("[REDACTED]", attempt.getInput().get("password"));

        // Verify outputs redacted
        assertEquals("[REDACTED]", attempt.getOutput().get("Authorization"));

        // Verify error redacted
        assertFalse(attempt.getError().contains("secret-raw-token"));
        assertTrue(attempt.getError().contains("[REDACTED]"));

        // Verify overall error redacted
        assertFalse(result.error().contains("secret-raw-token"));
        assertTrue(result.error().contains("[REDACTED]"));
    }
}
