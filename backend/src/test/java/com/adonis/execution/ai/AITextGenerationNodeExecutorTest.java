package com.adonis.execution.ai;

import com.adonis.ai.*;
import com.adonis.exception.WorkflowValidationException;
import com.adonis.execution.ExecutionContext;
import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.NodeExecutionResult;
import com.adonis.model.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AITextGenerationNodeExecutorTest {

    private AIProviderService providerService;
    private AITextGenerationNodeExecutor executor;

    @BeforeEach
    void setUp() {
        providerService = mock(AIProviderService.class);
        when(providerService.isSupported("openai")).thenReturn(true);
        when(providerService.isSupported("gemini")).thenReturn(true);
        when(providerService.getSupportedProviderNames()).thenReturn(java.util.List.of("openai", "gemini"));

        executor = new AITextGenerationNodeExecutor(providerService);
    }

    @Test
    void testSupports() {
        assertTrue(executor.supports("ai_text_generation"));
        assertTrue(executor.supports("ai-text-generation"));
        assertTrue(executor.supports("AI_TEXT_GENERATION"));
        assertTrue(executor.supports("aitextgeneration"));
        assertFalse(executor.supports("httpRequest"));
        assertFalse(executor.supports("generic"));
    }

    @Test
    void testValidationApiKeyRejected() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Hello");
        data.put("apiKey", "sk-secret123");

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        WorkflowValidationException ex = assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
        assertTrue(ex.getMessage().contains("must not contain API keys"));
    }

    @Test
    void testValidationMissingProvider() {
        Map<String, Object> data = new HashMap<>();
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Hello");

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testValidationUnsupportedProvider() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "claude");
        data.put("model", "claude-3");
        data.put("userPrompt", "Hello");

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testValidationMissingModel() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("userPrompt", "Hello");

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testValidationMissingPrompt() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testValidationInvalidTemperature() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Hello");
        data.put("temperature", 3.5);

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testValidationInvalidMaxTokens() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Hello");
        data.put("maxTokens", -10);

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testSuccessfulExecution() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("systemPrompt", "You are an assistant");
        data.put("userPrompt", "Write an introduction for {{name}}");
        data.put("temperature", 0.7);
        data.put("maxTokens", 500);

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        ExecutionContext context = new ExecutionContext("exec-1", "wf-1", "user-1", Instant.now());
        Map<String, Object> input = Map.of("name", "Abhijeet");

        AIResponse mockResponse = AIResponse.of(
                "Welcome Abhijeet! Great to have you here.",
                "openai",
                "gpt-4o-mini",
                AIUsage.of(15, 20, 35)
        );
        when(providerService.generate(any(AIRequest.class))).thenReturn(mockResponse);

        NodeExecutionResult result = executor.execute(node, input, context);

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals("ai-1", result.nodeId());
        assertEquals("ai_text_generation", result.nodeType());
        assertEquals("Welcome Abhijeet! Great to have you here.", result.output().get("text"));
        assertEquals("openai", result.output().get("provider"));
        assertEquals("gpt-4o-mini", result.output().get("model"));

        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) result.output().get("usage");
        assertEquals(15, usage.get("promptTokens"));
        assertEquals(20, usage.get("completionTokens"));
        assertEquals(35, usage.get("totalTokens"));

        // Verify request sent to provider had interpolated prompt
        verify(providerService).generate(argThat(req ->
                "Write an introduction for Abhijeet".equals(req.userPrompt()) &&
                        "You are an assistant".equals(req.systemPrompt()) &&
                        "openai".equals(req.provider()) &&
                        "gpt-4o-mini".equals(req.model())
        ));
    }

    @Test
    void testProviderFailurePropagated() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Hello");

        WorkflowNode node = new WorkflowNode("ai-1", "ai_text_generation", data);
        ExecutionContext context = new ExecutionContext("exec-1", "wf-1", "user-1", Instant.now());

        when(providerService.generate(any(AIRequest.class)))
                .thenThrow(new AIProviderException("Rate limit reached", "openai", "gpt-4o-mini", 429, true));

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertTrue(result.error().contains("Rate limit reached"));
        assertEquals(429, result.output().get("statusCode"));
        assertEquals("openai", result.output().get("provider"));
    }
}
