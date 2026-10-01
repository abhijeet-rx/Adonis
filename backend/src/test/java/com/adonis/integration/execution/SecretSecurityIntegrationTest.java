package com.adonis.integration.execution;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.LocalMockHttpServer;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.*;
import com.adonis.queue.ExecutionJob;
import com.adonis.queue.QueuedJobMessage;
import com.adonis.util.SecretRedactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Secret Security Integration Tests (Redaction in MongoDB, History, & Responses)")
class SecretSecurityIntegrationTest extends AdonisIntegrationTest {

    private static final String FAKE_OPENAI_KEY = "sk-test123456789012345678901234";
    private static final String FAKE_GEMINI_KEY = "AIzaSyAbcdef12345678901234567890123456";
    private static final String FAKE_BEARER = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret";

    @Test
    @DisplayName("Secret security: API keys and bearer tokens are redacted from Execution MongoDB documents and attempts")
    void secretSecurity_SecretsRedactedFromExecutionDocumentAndHistory() {
        // Mock server returns an error response that reflects the secret back
        mockHttpServer.setDefaultHttpResponse(500, "{\"error\":\"Failed with token " + FAKE_BEARER + " and " + FAKE_OPENAI_KEY + "\"}");

        Map<String, Object> httpData = Map.of(
                "url", mockHttpServer.getHttpEndpointUrl(),
                "method", "GET",
                "headers", Map.of("Authorization", FAKE_BEARER, "X-Api-Key", FAKE_GEMINI_KEY),
                "retry", Map.of("enabled", true, "maxRetries", 1, "initialBackoffMs", 20L, "backoffMultiplier", 1.0, "maxBackoffMs", 50L)
        );

        WorkflowNode trigger = TestDataFactory.createTriggerNode("trigger_1");
        WorkflowNode http = new WorkflowNode("http_1", "http", httpData);
        WorkflowEdge edge = TestDataFactory.createEdge("trigger_1", "http_1");

        Workflow workflow = TestDataFactory.createWorkflow(null, "user-sec", "Secret Flow",
                List.of(trigger, http), List.of(edge));
        workflow = workflowRepository.save(workflow);

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), "user-sec", "MANUAL");
        execution = executionRepository.save(execution);

        QueuedJobMessage message = new QueuedJobMessage("msg-sec-test",
                new ExecutionJob(execution.getId(), workflow.getId(), "user-sec", "MANUAL", Instant.now()));

        worker.processJob(message);

        // Fetch authoritative execution record from MongoDB
        WorkflowExecution savedExec = executionRepository.findById(execution.getId()).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, savedExec.getStatus());

        // 1. Error string must NOT contain raw secrets
        assertNotNull(savedExec.getError());
        assertFalse(savedExec.getError().contains(FAKE_OPENAI_KEY), "Raw OpenAI key must not appear in execution error");
        assertFalse(savedExec.getError().contains(FAKE_GEMINI_KEY), "Raw Gemini key must not appear in execution error");
        assertFalse(savedExec.getError().contains(FAKE_BEARER), "Bearer token must not appear in execution error");
        assertTrue(savedExec.getError().contains("[REDACTED]"), "Error message must contain [REDACTED]");

        // 2. NodeExecution attempts must NOT contain raw secrets in input, output, or error
        assertFalse(savedExec.getNodeExecutions().isEmpty());
        NodeExecution nodeExec = savedExec.getNodeExecutions().stream()
                .filter(n -> "http_1".equals(n.getNodeId())).findFirst().orElseThrow();

        if (nodeExec.getError() != null) {
            assertFalse(nodeExec.getError().contains(FAKE_OPENAI_KEY));
            assertFalse(nodeExec.getError().contains(FAKE_GEMINI_KEY));
        }

        for (NodeExecutionAttempt attempt : nodeExec.getAttempts()) {
            if (attempt.getError() != null) {
                assertFalse(attempt.getError().contains(FAKE_OPENAI_KEY));
                assertFalse(attempt.getError().contains(FAKE_GEMINI_KEY));
                assertFalse(attempt.getError().contains(FAKE_BEARER));
            }
            if (attempt.getInput() != null) {
                String inputStr = attempt.getInput().toString();
                assertFalse(inputStr.contains(FAKE_OPENAI_KEY));
                assertFalse(inputStr.contains(FAKE_GEMINI_KEY));
            }
            if (attempt.getOutput() != null) {
                String outputStr = attempt.getOutput().toString();
                assertFalse(outputStr.contains(FAKE_OPENAI_KEY));
                assertFalse(outputStr.contains(FAKE_GEMINI_KEY));
            }
        }
    }
}
