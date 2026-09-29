package com.adonis.execution;

import com.adonis.dto.ExecutionResponse;
import com.adonis.model.NodeExecution;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionSecurityHardeningTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    private WorkflowExecutionValidator validator;
    private WorkflowExecutionEngine engine;
    private WorkflowExecutionService executionService;

    @BeforeEach
    void setUp() {
        NodeExecutor triggerExecutor = new TriggerNodeExecutor();

        NodeExecutor customExecutor = new NodeExecutor() {
            @Override
            public boolean supports(String nodeType) {
                return "custom".equalsIgnoreCase(nodeType);
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
                Instant now = Instant.now();
                Map<String, Object> data = node.getData() != null ? node.getData() : Map.of();

                // If error flag set in test node
                if (data.containsKey("simulateError")) {
                    String rawError = String.valueOf(data.get("simulateError"));
                    return NodeExecutionResult.failure(node.getId(), node.getType(), now, now, input, rawError);
                }

                // If output configured in test node
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) data.getOrDefault("output", Map.of());
                return NodeExecutionResult.success(node.getId(), node.getType(), now, now, input, output);
            }
        };

        List<NodeExecutor> executors = List.of(triggerExecutor, customExecutor);
        validator = new WorkflowExecutionValidator(executors);
        engine = new WorkflowExecutionEngine(executors);
        executionService = new WorkflowExecutionService(workflowRepository, validator, engine, executionRepository);

        lenient().when(executionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(inv -> {
                    WorkflowExecution exec = inv.getArgument(0);
                    if (exec.getId() == null) {
                        exec.setId("exec-sec-test");
                    }
                    return exec;
                });
    }

    /**
     * Test 1 — API output redaction:
     * Node produces sensitive output (Authorization, apiKey, password).
     * Verify that API response contains [REDACTED] and DOES NOT contain original secrets.
     */
    @Test
    void test1_apiOutputRedaction_RemovesSensitiveFields() {
        String secretToken = "Bearer very-secret-token";
        String secretApiKey = "super-secret-key";
        String secretPassword = "secret-password";

        Map<String, Object> sensitiveOutput = Map.of(
                "headers", Map.of("Authorization", secretToken),
                "apiKey", secretApiKey,
                "password", secretPassword,
                "publicData", "visible-value"
        );

        Workflow workflow = new Workflow();
        workflow.setId("wf-output-sec");
        workflow.setUserId("user-sec");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of("triggerType", "manual")),
                new WorkflowNode("node-1", "custom", Map.of("output", sensitiveOutput))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "node-1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-output-sec", "user-sec"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult apiResponse = executionService.executeWorkflow("wf-output-sec", "user-sec");

        assertNotNull(apiResponse);
        assertEquals(ExecutionStatus.SUCCESS, apiResponse.status());
        assertEquals(2, apiResponse.nodes().size());

        NodeExecutionResult nodeResult = apiResponse.nodes().get(1);
        Map<String, Object> nodeOutput = nodeResult.output();

        // Verify values are redacted
        assertEquals("[REDACTED]", nodeOutput.get("apiKey"));
        assertEquals("[REDACTED]", nodeOutput.get("password"));
        assertEquals("visible-value", nodeOutput.get("publicData"));

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) nodeOutput.get("headers");
        assertEquals("[REDACTED]", headers.get("Authorization"));

        // Strictly verify raw secrets are NOT present anywhere in node output
        String stringified = nodeOutput.toString();
        assertFalse(stringified.contains(secretToken), "Raw authorization token leaked in API output");
        assertFalse(stringified.contains(secretApiKey), "Raw API key leaked in API output");
        assertFalse(stringified.contains(secretPassword), "Raw password leaked in API output");
    }

    /**
     * Test 2 — API input redaction:
     * Node receives input with sensitive keys and values.
     * Verify they are redacted in the API response.
     */
    @Test
    void test2_apiInputRedaction_RemovesSensitiveInput() {
        String secretToken = "my-secret-access-token-123";
        String secretApiKey = "secret-api-key-999";

        // First node outputs secrets that flow into the second node as input
        Map<String, Object> node1Output = Map.of(
                "accessToken", secretToken,
                "api_key", secretApiKey,
                "safeParam", "ok-param"
        );

        Workflow workflow = new Workflow();
        workflow.setId("wf-input-sec");
        workflow.setUserId("user-sec");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of()),
                new WorkflowNode("n1", "custom", Map.of("output", node1Output)),
                new WorkflowNode("n2", "custom", Map.of())
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e0", "t1", "n1"),
                new WorkflowEdge("e1", "n1", "n2")
        ));

        when(workflowRepository.findByIdAndUserId("wf-input-sec", "user-sec"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult apiResponse = executionService.executeWorkflow("wf-input-sec", "user-sec");

        assertEquals(3, apiResponse.nodes().size());
        NodeExecutionResult n2Result = apiResponse.nodes().get(2);

        Map<String, Object> input = n2Result.input();
        assertEquals("[REDACTED]", input.get("accessToken"));
        assertEquals("[REDACTED]", input.get("api_key"));
        assertEquals("ok-param", input.get("safeParam"));

        String stringified = input.toString();
        assertFalse(stringified.contains(secretToken), "Raw access token leaked in node input");
        assertFalse(stringified.contains(secretApiKey), "Raw API key leaked in node input");
    }

    /**
     * Test 3 — Node error redaction:
     * Node fails with an error string containing sensitive data (URL with credentials, tokens).
     * Verify the API response does not expose the secrets.
     */
    @Test
    void test3_nodeErrorRedaction_SanitizesNodeErrorMessage() {
        String rawPassword = "superSecretPassword";
        String rawKey = "api-key-12345";
        String rawError = "Connection failed to https://admin:" + rawPassword + "@service.corp:8443 with apiKey=" + rawKey;

        Workflow workflow = new Workflow();
        workflow.setId("wf-err-sec");
        workflow.setUserId("user-sec");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of()),
                new WorkflowNode("errNode", "custom", Map.of("simulateError", rawError))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "errNode")
        ));

        when(workflowRepository.findByIdAndUserId("wf-err-sec", "user-sec"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult apiResponse = executionService.executeWorkflow("wf-err-sec", "user-sec");

        assertEquals(ExecutionStatus.FAILED, apiResponse.status());
        NodeExecutionResult nodeRes = apiResponse.nodes().get(1);
        String nodeError = nodeRes.error();

        assertNotNull(nodeError);
        assertFalse(nodeError.contains(rawPassword), "Raw password leaked in node error");
        assertFalse(nodeError.contains(rawKey), "Raw API key leaked in node error");
        assertTrue(nodeError.contains("https://admin:[REDACTED]@service.corp:8443"));
        assertTrue(nodeError.contains("apiKey=[REDACTED]"));
    }

    /**
     * Test 4 — Overall execution error redaction:
     * Simulate overall execution failure containing a secret.
     * Verify ExecutionResponse.error and WorkflowExecutionResult.error do not expose original secret.
     */
    @Test
    void test4_overallExecutionErrorRedaction_SanitizesOverallError() {
        String secretValue = "topSecretDbPassword";
        String rawError = "Failed executing node: password: " + secretValue;

        Workflow workflow = new Workflow();
        workflow.setId("wf-overall-err");
        workflow.setUserId("user-sec");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of()),
                new WorkflowNode("failingNode", "custom", Map.of("simulateError", rawError))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "failingNode")
        ));

        when(workflowRepository.findByIdAndUserId("wf-overall-err", "user-sec"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult apiResponse = executionService.executeWorkflow("wf-overall-err", "user-sec");

        assertEquals(ExecutionStatus.FAILED, apiResponse.status());
        assertFalse(apiResponse.error().contains(secretValue), "Raw secret leaked in overall WorkflowExecutionResult error");
        assertTrue(apiResponse.error().contains("password: [REDACTED]"));

        // Also check ExecutionResponse DTO mapping
        WorkflowExecution model = new WorkflowExecution();
        model.setId("exec-dto-err");
        model.setWorkflowId("wf-overall-err");
        model.setUserId("user-sec");
        model.setStatus(ExecutionStatus.FAILED);
        model.setError("Database connection error with secretToken=" + secretValue);

        ExecutionResponse dto = ExecutionResponse.fromModel(model);
        assertFalse(dto.error().contains(secretValue), "Raw secret leaked in ExecutionResponse.error");
        assertTrue(dto.error().contains("secretToken=[REDACTED]"));
    }

    /**
     * Test 5 — Persistence and API consistency:
     * Verify that sensitive data is redacted both in the MongoDB persisted record
     * and in the API execution response. The original secret must not be present in either.
     */
    @Test
    void test5_persistenceAndApiConsistency_BothRedactedIdentically() {
        String rawSecret = "super-secret-authorization-token";
        Map<String, Object> sensitiveOutput = Map.of(
                "authorization", "Bearer " + rawSecret,
                "secretKey", "hidden-secret-xyz"
        );

        Workflow workflow = new Workflow();
        workflow.setId("wf-consistency");
        workflow.setUserId("user-sec");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of()),
                new WorkflowNode("step1", "custom", Map.of("output", sensitiveOutput))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "step1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-consistency", "user-sec"))
                .thenReturn(Optional.of(workflow));

        // Execute workflow
        WorkflowExecutionResult apiResponse = executionService.executeWorkflow("wf-consistency", "user-sec");

        // 1. Verify API response
        NodeExecutionResult apiNode = apiResponse.nodes().get(1);
        assertEquals("[REDACTED]", apiNode.output().get("authorization"));
        assertEquals("[REDACTED]", apiNode.output().get("secretKey"));
        assertFalse(apiNode.output().toString().contains(rawSecret));
        assertFalse(apiNode.output().toString().contains("hidden-secret-xyz"));

        // 2. Verify MongoDB persisted entity
        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository, atLeastOnce()).save(captor.capture());

        WorkflowExecution savedEntity = captor.getValue();
        assertEquals(ExecutionStatus.SUCCESS, savedEntity.getStatus());
        assertEquals(2, savedEntity.getNodeExecutions().size());

        NodeExecution dbNode = savedEntity.getNodeExecutions().get(1);
        assertEquals("[REDACTED]", dbNode.getOutput().get("authorization"));
        assertEquals("[REDACTED]", dbNode.getOutput().get("secretKey"));
        assertFalse(dbNode.getOutput().toString().contains(rawSecret), "Secret leaked in MongoDB entity");
        assertFalse(dbNode.getOutput().toString().contains("hidden-secret-xyz"), "Secret leaked in MongoDB entity");
    }
}
