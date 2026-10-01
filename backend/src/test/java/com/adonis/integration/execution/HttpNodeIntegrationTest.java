package com.adonis.integration.execution;

import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.FailureClassifier;
import com.adonis.execution.NodeExecutionResult;
import com.adonis.execution.WorkflowExecutionResult;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.LocalMockHttpServer;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HTTP Node Integration Tests (Local Test HTTP Server)")
class HttpNodeIntegrationTest extends AdonisIntegrationTest {

    @Autowired
    private FailureClassifier failureClassifier;

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 204})
    @DisplayName("HTTP node success codes: 200, 201, 204 complete with SUCCESS status")
    void httpNode_SuccessStatusCodes(int statusCode) {
        String body = statusCode == 204 ? "" : "{\"success\":true}";
        mockHttpServer.enqueueHttpResponse(statusCode, body);

        Workflow workflow = TestDataFactory.createHttpWorkflow("user-http", "Success Flow " + statusCode,
                mockHttpServer.getHttpEndpointUrl(), "GET");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-http", "exec-" + statusCode);

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        NodeExecutionResult httpResult = result.nodes().stream()
                .filter(n -> "http_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, httpResult.status());
        assertEquals(statusCode, httpResult.output().get("statusCode"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    @DisplayName("HTTP node non-retryable 4xx errors: classified as non-retryable")
    void httpNode_NonRetryableClientErrors(int statusCode) {
        mockHttpServer.enqueueHttpResponse(statusCode, "{\"error\":\"Client Error " + statusCode + "\"}");

        Workflow workflow = TestDataFactory.createHttpWorkflow("user-http", "Client Error " + statusCode,
                mockHttpServer.getHttpEndpointUrl(), "GET");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-http", "exec-" + statusCode);

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult httpResult = result.nodes().stream()
                .filter(n -> "http_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, httpResult.status());
        assertFalse(failureClassifier.isRetryable(httpResult), "HTTP " + statusCode + " must be classified as NON-RETRYABLE");
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    @DisplayName("HTTP node retryable errors: 408, 429, 500, 502, 503, 504 classified as retryable")
    void httpNode_RetryableErrors(int statusCode) {
        mockHttpServer.enqueueHttpResponse(statusCode, "{\"error\":\"Transient Error " + statusCode + "\"}");

        Workflow workflow = TestDataFactory.createHttpWorkflow("user-http", "Transient Error " + statusCode,
                mockHttpServer.getHttpEndpointUrl(), "GET");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-http", "exec-" + statusCode);

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult httpResult = result.nodes().stream()
                .filter(n -> "http_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, httpResult.status());
        assertTrue(failureClassifier.isRetryable(httpResult), "HTTP " + statusCode + " must be classified as RETRYABLE");
    }

    @Test
    @DisplayName("HTTP node timeout: delayed response exceeding timeout classified as retryable")
    void httpNode_Timeout_ClassifiedAsRetryable() {
        // Enqueue delayed response (2000ms delay)
        mockHttpServer.enqueueHttpResponse(LocalMockHttpServer.MockResponse.delayed(200, "{\"delayed\":true}", Duration.ofMillis(2000)));

        // Configure HTTP node with 200ms read timeout
        WorkflowNode trigger = TestDataFactory.createTriggerNode("trigger_1");
        WorkflowNode http = TestDataFactory.createHttpNode("http_1", mockHttpServer.getHttpEndpointUrl(), "GET", Map.of(), null);
        http.getData().put("timeoutMs", 200);

        Workflow workflow = TestDataFactory.createWorkflow(null, "user-http", "Timeout Flow",
                List.of(trigger, http), List.of(TestDataFactory.createEdge("trigger_1", "http_1")));

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-http", "exec-timeout");

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult httpResult = result.nodes().stream()
                .filter(n -> "http_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, httpResult.status());
        assertTrue(failureClassifier.isRetryable(httpResult), "Timeout error must be classified as RETRYABLE");
    }

    @Test
    @DisplayName("HTTP node connection failure: invalid host/port classified as retryable")
    void httpNode_ConnectionFailure_ClassifiedAsRetryable() {
        // Port 59999 is inactive
        Workflow workflow = TestDataFactory.createHttpWorkflow("user-http", "Connection Refused Flow",
                "http://127.0.0.1:59999/unreachable", "GET");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-http", "exec-conn-fail");

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult httpResult = result.nodes().stream()
                .filter(n -> "http_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, httpResult.status());
        assertTrue(failureClassifier.isRetryable(httpResult), "Connection failure must be classified as RETRYABLE");
    }
}
