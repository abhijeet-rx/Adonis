package com.adonis.execution.ai;

import com.adonis.ai.AIProviderService;
import com.adonis.exception.WorkflowValidationException;
import com.adonis.execution.ExecutionContext;
import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.NodeExecutionResult;
import com.adonis.model.WorkflowNode;
import com.adonis.util.SecretRedactor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AISecurityTest {

    @Test
    void testNodeValidationRejectsDirectApiKeyInConfiguration() {
        AIProviderService providerService = mock(AIProviderService.class);
        AITextGenerationNodeExecutor textExecutor = new AITextGenerationNodeExecutor(providerService);
        AIStructuredOutputNodeExecutor structExecutor = new AIStructuredOutputNodeExecutor(providerService);

        Map<String, Object> textData = new HashMap<>();
        textData.put("provider", "openai");
        textData.put("model", "gpt-4o");
        textData.put("userPrompt", "Hi");
        textData.put("apiKey", "sk-1234567890abcdef1234567890");

        assertThrows(WorkflowValidationException.class, () ->
                textExecutor.validate(new WorkflowNode("n1", "ai_text_generation", textData)));

        Map<String, Object> structData = new HashMap<>();
        structData.put("provider", "openai");
        structData.put("model", "gpt-4o");
        structData.put("userPrompt", "Hi");
        structData.put("jsonSchema", "{\"type\":\"object\"}");
        structData.put("api_key", "sk-1234567890abcdef1234567890");

        assertThrows(WorkflowValidationException.class, () ->
                structExecutor.validate(new WorkflowNode("n2", "ai_structured_output", structData)));
    }

    @Test
    void testSecretRedactorRedactsOpenAIAndGeminiKeys() {
        String openAiKey = "sk-proj-1234567890abcdefghijklmnopqrstuvwxyz";
        String geminiKey = "AIzaSyB1234567890abcdef1234567890abcdef";

        String textWithKeys = "Received error from OpenAI with key " + openAiKey + " and Gemini " + geminiKey;
        String redacted = SecretRedactor.redactString(textWithKeys);

        assertFalse(redacted.contains(openAiKey));
        assertFalse(redacted.contains(geminiKey));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    void testSanitizeNodeExecutionResultCleansAnyLeakedKeys() {
        String openAiKey = "sk-1234567890abcdef1234567890";
        Map<String, Object> input = Map.of(
                "prompt", "Call API with " + openAiKey,
                "openai_key", openAiKey
        );
        Map<String, Object> output = Map.of(
                "error", "Failed request using key: " + openAiKey
        );

        NodeExecutionResult raw = NodeExecutionResult.failure(
                "ai-1",
                "ai_text_generation",
                Instant.now(),
                Instant.now(),
                input,
                output,
                "Error with key: " + openAiKey
        );

        NodeExecutionResult sanitized = SecretRedactor.sanitizeNodeResult(raw);

        // Verify input map redacted
        assertEquals("[REDACTED]", sanitized.input().get("openai_key"));
        assertFalse(sanitized.input().get("prompt").toString().contains(openAiKey));

        // Verify output map redacted
        assertFalse(sanitized.output().get("error").toString().contains(openAiKey));

        // Verify error string redacted
        assertFalse(sanitized.error().contains(openAiKey));
        assertTrue(sanitized.error().contains("[REDACTED]"));
    }
}
