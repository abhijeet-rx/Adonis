package com.adonis.integration.execution;

import com.adonis.ai.AIProperties;
import com.adonis.ai.AIProviderException;
import com.adonis.ai.AIRequest;
import com.adonis.ai.AIResponse;
import com.adonis.ai.provider.GeminiProvider;
import com.adonis.ai.provider.OpenAIProvider;
import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.FailureClassifier;
import com.adonis.execution.NodeExecutionResult;
import com.adonis.execution.WorkflowExecutionResult;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.LocalMockHttpServer;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AI Node Integration Tests (Fake OpenAI & Gemini Endpoints)")
class AINodeIntegrationTest extends AdonisIntegrationTest {

    @Autowired
    private OpenAIProvider openAIProvider;

    @Autowired
    private GeminiProvider geminiProvider;

    @Autowired
    private FailureClassifier failureClassifier;

    @Test
    @DisplayName("OpenAI provider success: completes through execution engine via local fake API")
    void openAiProvider_Success_CompletesViaLocalFakeServer() {
        String fakeCompletion = "The summary is complete.";
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson(fakeCompletion, "gpt-4o", 15, 25));

        Workflow workflow = TestDataFactory.createAiTextWorkflow("user-ai", "OpenAI Success Flow",
                "openai", "gpt-4o", "Summarize this article");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-openai-ok");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, aiResult.status());
        assertEquals(fakeCompletion, aiResult.output().get("text"));
        assertEquals("openai", aiResult.output().get("provider"));
        assertEquals("gpt-4o", aiResult.output().get("model"));
        assertNotNull(aiResult.output().get("usage"));

        // Verify request received by fake server
        assertEquals(1, mockHttpServer.getRecordedOpenAiRequests().size());
        LocalMockHttpServer.RecordedHttpRequest req = mockHttpServer.getRecordedOpenAiRequests().get(0);
        assertTrue(req.getHeader("Authorization").startsWith("Bearer sk-test"));
        assertTrue(req.body().contains("Summarize this article"));
    }

    @Test
    @DisplayName("Gemini provider success: completes through execution engine via local fake API")
    void geminiProvider_Success_CompletesViaLocalFakeServer() {
        String fakeGeminiText = "Gemini generated insight.";
        mockHttpServer.enqueueGeminiResponse(200,
                LocalMockHttpServer.buildGeminiSuccessJson(fakeGeminiText, "gemini-1.5-flash", 20, 30));

        Workflow workflow = TestDataFactory.createAiTextWorkflow("user-ai", "Gemini Success Flow",
                "gemini", "gemini-1.5-flash", "Explain recursion");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-gemini-ok");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, aiResult.status());
        assertEquals(fakeGeminiText, aiResult.output().get("text"));
        assertEquals("gemini", aiResult.output().get("provider"));
        assertEquals("gemini-1.5-flash", aiResult.output().get("model"));

        // Verify request received by fake server
        assertEquals(1, mockHttpServer.getRecordedGeminiRequests().size());
        LocalMockHttpServer.RecordedHttpRequest req = mockHttpServer.getRecordedGeminiRequests().get(0);
        assertNotNull(req.getHeader("x-goog-api-key"));
        assertTrue(req.body().contains("Explain recursion"));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    @DisplayName("AI provider retryable errors: 429, 500, 503 classified as retryable")
    void aiProvider_RetryableErrors_ClassifiedCorrectly(int statusCode) {
        mockHttpServer.enqueueOpenAiResponse(statusCode,
                "{\"error\":{\"message\":\"Rate limit or server error\",\"type\":\"server_error\"}}");

        Workflow workflow = TestDataFactory.createAiTextWorkflow("user-ai", "OpenAI Error " + statusCode,
                "openai", "gpt-4o", "Hello");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-ai-err-" + statusCode);

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, aiResult.status());
        assertTrue(failureClassifier.isRetryable(aiResult), "AI status " + statusCode + " must be classified as RETRYABLE");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404})
    @DisplayName("AI provider non-retryable errors: 400, 401, 404 classified as non-retryable")
    void aiProvider_NonRetryableErrors_ClassifiedCorrectly(int statusCode) {
        mockHttpServer.enqueueOpenAiResponse(statusCode,
                "{\"error\":{\"message\":\"Invalid request or credentials\",\"type\":\"invalid_request_error\"}}");

        Workflow workflow = TestDataFactory.createAiTextWorkflow("user-ai", "OpenAI Error " + statusCode,
                "openai", "gpt-4o", "Hello");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-ai-err-" + statusCode);

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, aiResult.status());
        assertFalse(failureClassifier.isRetryable(aiResult), "AI status " + statusCode + " must be classified as NON-RETRYABLE");
    }

    @Test
    @DisplayName("AI provider timeout: request timeout classified as retryable")
    void aiProvider_Timeout_ClassifiedAsRetryable() {
        // Enqueue delayed OpenAI response exceeding read timeout
        mockHttpServer.enqueueOpenAiResponse(LocalMockHttpServer.MockResponse.delayed(200,
                LocalMockHttpServer.buildOpenAiSuccessJson("late", "gpt-4o", 1, 1), Duration.ofMillis(5000)));

        Workflow workflow = TestDataFactory.createAiTextWorkflow("user-ai", "OpenAI Timeout",
                "openai", "gpt-4o", "Slow prompt");

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-ai-timeout");

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, aiResult.status());
        assertTrue(failureClassifier.isRetryable(aiResult), "AI timeout must be classified as RETRYABLE");
    }
}
