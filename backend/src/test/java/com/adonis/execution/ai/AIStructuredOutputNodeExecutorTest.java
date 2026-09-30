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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AIStructuredOutputNodeExecutorTest {

    private AIProviderService providerService;
    private AIStructuredOutputNodeExecutor executor;

    private final String resumeSchema = """
            {
              "type": "object",
              "required": ["name", "email", "skills", "yearsOfExperience"],
              "properties": {
                "name": {"type": "string"},
                "email": {"type": "string"},
                "skills": {
                  "type": "array",
                  "items": {"type": "string"}
                },
                "yearsOfExperience": {"type": "number"}
              }
            }
            """;

    @BeforeEach
    void setUp() {
        providerService = mock(AIProviderService.class);
        when(providerService.isSupported("openai")).thenReturn(true);
        when(providerService.isSupported("gemini")).thenReturn(true);
        when(providerService.getSupportedProviderNames()).thenReturn(List.of("openai", "gemini"));

        executor = new AIStructuredOutputNodeExecutor(providerService);
    }

    @Test
    void testSupports() {
        assertTrue(executor.supports("ai_structured_output"));
        assertTrue(executor.supports("ai-structured-output"));
        assertTrue(executor.supports("AI_STRUCTURED_OUTPUT"));
        assertTrue(executor.supports("aistructuredoutput"));
        assertFalse(executor.supports("ai_text_generation"));
        assertFalse(executor.supports("httpRequest"));
    }

    @Test
    void testValidationMissingSchema() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Extract details");

        WorkflowNode node = new WorkflowNode("ai-struct", "ai_structured_output", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testValidationInvalidSchema() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Extract details");
        data.put("jsonSchema", "not a valid json schema");

        WorkflowNode node = new WorkflowNode("ai-struct", "ai_structured_output", data);
        assertThrows(WorkflowValidationException.class, () -> executor.validate(node));
    }

    @Test
    void testSuccessfulStructuredOutputExecution() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Extract resume for {{candidateName}}");
        data.put("jsonSchema", resumeSchema);

        WorkflowNode node = new WorkflowNode("ai-struct", "ai_structured_output", data);
        ExecutionContext context = new ExecutionContext("exec-1", "wf-1", "user-1", Instant.now());
        Map<String, Object> input = Map.of("candidateName", "John Smith");

        String aiResponseJson = """
                {
                  "name": "John Smith",
                  "email": "john@example.com",
                  "skills": ["Java", "Docker", "Kubernetes"],
                  "yearsOfExperience": 7.0
                }
                """;

        AIResponse mockResponse = AIResponse.of(
                aiResponseJson,
                "openai",
                "gpt-4o-mini",
                AIUsage.of(30, 40, 70)
        );
        when(providerService.generate(any(AIRequest.class))).thenReturn(mockResponse);

        NodeExecutionResult result = executor.execute(node, input, context);

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertNotNull(result.output().get("structured"));

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.output().get("structured");
        assertEquals("John Smith", structured.get("name"));
        assertEquals("john@example.com", structured.get("email"));
        assertEquals(7.0, ((Number) structured.get("yearsOfExperience")).doubleValue());
        assertTrue(structured.get("skills") instanceof List<?>);
    }

    @Test
    void testMalformedJsonFailsNodeExecution() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Extract resume");
        data.put("jsonSchema", resumeSchema);

        WorkflowNode node = new WorkflowNode("ai-struct", "ai_structured_output", data);
        ExecutionContext context = new ExecutionContext("exec-1", "wf-1", "user-1", Instant.now());

        AIResponse mockResponse = AIResponse.of(
                "Here is the result: { name: 'John Smith', missing end bracket",
                "openai",
                "gpt-4o-mini",
                AIUsage.of(20, 20, 40)
        );
        when(providerService.generate(any(AIRequest.class))).thenReturn(mockResponse);

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertTrue(result.error().contains("schema validation failed") || result.error().contains("failed to parse as JSON"));
    }

    @Test
    void testSchemaMismatchMissingRequiredFieldFailsNodeExecution() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("model", "gpt-4o-mini");
        data.put("userPrompt", "Extract resume");
        data.put("jsonSchema", resumeSchema);

        WorkflowNode node = new WorkflowNode("ai-struct", "ai_structured_output", data);
        ExecutionContext context = new ExecutionContext("exec-1", "wf-1", "user-1", Instant.now());

        // Missing "email"
        String incompleteJson = """
                {
                  "name": "John Smith",
                  "skills": ["Java"],
                  "yearsOfExperience": 3
                }
                """;

        AIResponse mockResponse = AIResponse.of(incompleteJson, "openai", "gpt-4o-mini", AIUsage.empty());
        when(providerService.generate(any(AIRequest.class))).thenReturn(mockResponse);

        NodeExecutionResult result = executor.execute(node, Map.of(), context);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertTrue(result.error().contains("missing required property 'email'"));
    }
}
