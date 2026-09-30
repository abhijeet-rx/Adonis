package com.adonis.execution.ai;

import com.adonis.ai.AIProviderException;
import com.adonis.ai.AIProviderService;
import com.adonis.ai.AIRequest;
import com.adonis.ai.AIResponse;
import com.adonis.ai.AIUsage;
import com.adonis.execution.*;
import com.adonis.model.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AINodeRetryTest {

    private AIProviderService aiProviderService;
    private RetryPolicy retryPolicy;
    private AITextGenerationNodeExecutor executor;

    @BeforeEach
    void setUp() {
        aiProviderService = mock(AIProviderService.class);
        when(aiProviderService.isSupported("openai")).thenReturn(true);
        when(aiProviderService.getSupportedProviderNames()).thenReturn(java.util.List.of("openai"));

        executor = new AITextGenerationNodeExecutor(aiProviderService);
        FailureClassifier failureClassifier = new FailureClassifier();
        retryPolicy = new RetryPolicy(failureClassifier, RetryDelayStrategy.noOp());
    }

    @Test
    void testRateLimit429RetriesAndSucceeds() {
        Map<String, Object> retryMap = Map.of(
                "enabled", true,
                "maxRetries", 3,
                "initialBackoffMs", 100
        );

        Map<String, Object> nodeData = new HashMap<>();
        nodeData.put("provider", "openai");
        nodeData.put("model", "gpt-4o-mini");
        nodeData.put("userPrompt", "Tell a joke");
        nodeData.put("retry", retryMap);

        WorkflowNode node = new WorkflowNode("ai-retry-node", "ai_text_generation", nodeData);
        ExecutionContext context = new ExecutionContext("exec-retry-1", "wf-1", "user-1", Instant.now());

        // 1st attempt: 429 Too Many Requests; 2nd attempt: Success!
        when(aiProviderService.generate(any(AIRequest.class)))
                .thenThrow(new AIProviderException("Rate limit reached", "openai", "gpt-4o-mini", 429, true))
                .thenReturn(AIResponse.of("Why did the chicken cross the road?", "openai", "gpt-4o-mini", AIUsage.of(10, 10, 20)));

        NodeExecutionResult result = retryPolicy.executeWithRetry(node, Map.of(), context, executor);

        assertEquals(com.adonis.execution.ExecutionStatus.SUCCESS, result.status());
        assertEquals(1, result.retryCount()); // 1 retry performed
        assertEquals(2, result.attempts().size()); // 2 total attempts

        // Verify attempts
        assertEquals(ExecutionStatus.FAILED, result.attempts().get(0).getStatus());
        assertEquals(ExecutionStatus.SUCCESS, result.attempts().get(1).getStatus());
    }

    @Test
    void testUnauthorized401DoesNotRetry() {
        Map<String, Object> retryMap = Map.of(
                "enabled", true,
                "maxRetries", 3,
                "initialBackoffMs", 100
        );

        Map<String, Object> nodeData = new HashMap<>();
        nodeData.put("provider", "openai");
        nodeData.put("model", "gpt-4o-mini");
        nodeData.put("userPrompt", "Tell a joke");
        nodeData.put("retry", retryMap);

        WorkflowNode node = new WorkflowNode("ai-no-retry-node", "ai_text_generation", nodeData);
        ExecutionContext context = new ExecutionContext("exec-no-retry-1", "wf-1", "user-1", Instant.now());

        // 401 Unauthorized
        when(aiProviderService.generate(any(AIRequest.class)))
                .thenThrow(new AIProviderException("Unauthorized: Invalid API key", "openai", "gpt-4o-mini", 401, false));

        NodeExecutionResult result = retryPolicy.executeWithRetry(node, Map.of(), context, executor);

        assertEquals(com.adonis.execution.ExecutionStatus.FAILED, result.status());
        assertEquals(0, result.retryCount()); // No retries performed
        assertEquals(1, result.attempts().size()); // Only 1 attempt
        verify(aiProviderService, times(1)).generate(any(AIRequest.class));
    }
}
