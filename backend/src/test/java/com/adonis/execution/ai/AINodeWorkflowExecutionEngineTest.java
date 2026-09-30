package com.adonis.execution.ai;

import com.adonis.ai.AIProviderService;
import com.adonis.ai.AIRequest;
import com.adonis.ai.AIResponse;
import com.adonis.ai.AIUsage;
import com.adonis.execution.*;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AINodeWorkflowExecutionEngineTest {

    private AIProviderService aiProviderService;
    private HttpClient mockHttpClient;
    private WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        aiProviderService = mock(AIProviderService.class);
        when(aiProviderService.isSupported("openai")).thenReturn(true);
        when(aiProviderService.isSupported("gemini")).thenReturn(true);
        when(aiProviderService.getSupportedProviderNames()).thenReturn(List.of("openai", "gemini"));

        mockHttpClient = mock(HttpClient.class);

        TriggerNodeExecutor triggerExecutor = new TriggerNodeExecutor();
        HttpRequestNodeExecutor httpExecutor = new HttpRequestNodeExecutor(mockHttpClient);
        AITextGenerationNodeExecutor aiTextExecutor = new AITextGenerationNodeExecutor(aiProviderService);
        AIStructuredOutputNodeExecutor aiStructExecutor = new AIStructuredOutputNodeExecutor(aiProviderService);
        GenericNodeExecutor genericExecutor = new GenericNodeExecutor();

        List<NodeExecutor> executors = List.of(triggerExecutor, httpExecutor, aiTextExecutor, aiStructExecutor, genericExecutor);
        FailureClassifier failureClassifier = new FailureClassifier();
        RetryPolicy retryPolicy = new RetryPolicy(failureClassifier, RetryDelayStrategy.noOp());
        engine = new WorkflowExecutionEngine(executors, retryPolicy, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTriggerToAIToHttpWorkflowFlow() throws Exception {
        // Workflow: Trigger -> AI Text Gen -> HTTP Request
        WorkflowNode triggerNode = new WorkflowNode("node-trigger", "trigger", Map.of());
        WorkflowNode aiNode = new WorkflowNode("node-ai", "ai_text_generation", Map.of(
                "provider", "openai",
                "model", "gpt-4o-mini",
                "userPrompt", "Summarize trigger payload: {{node-trigger.output.data.message}}"
        ));
        WorkflowNode httpNode = new WorkflowNode("node-http", "httpRequest", Map.of(
                "url", "https://api.example.com/log",
                "method", "POST",
                "body", "{\"summary\": \"{{node-ai.output.text}}\"}"
        ));

        WorkflowEdge edge1 = new WorkflowEdge("e1", "node-trigger", "node-ai");
        WorkflowEdge edge2 = new WorkflowEdge("e2", "node-ai", "node-http");

        Workflow workflow = Workflow.create(
                "user-1", "AI to HTTP Flow", "Test workflow",
                WorkflowStatus.ACTIVE,
                List.of(triggerNode, aiNode, httpNode),
                List.of(edge1, edge2)
        );

        // Mock AI response
        when(aiProviderService.generate(any(AIRequest.class))).thenReturn(
                AIResponse.of("AI Summary: User registered successfully", "openai", "gpt-4o-mini", AIUsage.of(10, 15, 25))
        );

        // Mock HTTP response
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"ok\"}");
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (k, v) -> true));
        when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        TriggerContext triggerContext = new TriggerContext(
                com.adonis.model.WorkflowTriggerType.MANUAL,
                Map.of("message", "User registration event"),
                Map.of("userId", "user-1")
        );
        WorkflowExecutionResult result = engine.execute(
                workflow,
                List.of(triggerNode, aiNode, httpNode),
                "user-1",
                "exec-ai-flow-1",
                triggerContext
        );

        if (result.error() != null) {
            System.err.println("DEBUG ERROR: " + result.error());
        }
        assertEquals(ExecutionStatus.SUCCESS, result.status(), "Execution failed: " + result.error());
        assertEquals(3, result.nodes().size());

        NodeExecutionResult aiResult = result.nodes().get(1);
        assertEquals(ExecutionStatus.SUCCESS, aiResult.status());
        assertEquals("AI Summary: User registered successfully", aiResult.output().get("text"));

        NodeExecutionResult httpResult = result.nodes().get(2);
        assertEquals(ExecutionStatus.SUCCESS, httpResult.status());
        assertEquals(200, httpResult.output().get("statusCode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHttpToAIStructuredOutputWorkflowFlow() throws Exception {
        // Workflow: Trigger -> HTTP -> AI Structured Output
        WorkflowNode triggerNode = new WorkflowNode("node-trigger", "trigger", Map.of());
        WorkflowNode httpNode = new WorkflowNode("node-http", "httpRequest", Map.of(
                "url", "https://api.example.com/raw-resume",
                "method", "GET"
        ));
        String schema = """
                {
                  "type": "object",
                  "required": ["fullName", "email"],
                  "properties": {
                    "fullName": {"type": "string"},
                    "email": {"type": "string"}
                  }
                }
                """;
        WorkflowNode aiNode = new WorkflowNode("node-ai-struct", "ai_structured_output", Map.of(
                "provider", "gemini",
                "model", "gemini-1.5-flash",
                "userPrompt", "Extract resume: {{node-http.output.body}}",
                "jsonSchema", schema
        ));

        WorkflowEdge edge1 = new WorkflowEdge("e1", "node-trigger", "node-http");
        WorkflowEdge edge2 = new WorkflowEdge("e2", "node-http", "node-ai-struct");

        Workflow workflow = Workflow.create(
                "user-1", "HTTP to AI Structured Output", "Test workflow",
                WorkflowStatus.ACTIVE,
                List.of(triggerNode, httpNode, aiNode),
                List.of(edge1, edge2)
        );

        // Mock HTTP response
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("Candidate: Bob Miller, Contact: bob@example.com");
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (k, v) -> true));
        when(mockHttpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        // Mock AI Structured Output
        String aiJson = "{\"fullName\": \"Bob Miller\", \"email\": \"bob@example.com\"}";
        when(aiProviderService.generate(any(AIRequest.class))).thenReturn(
                AIResponse.of(aiJson, "gemini", "gemini-1.5-flash", AIUsage.of(20, 20, 40))
        );

        WorkflowExecutionResult result = engine.execute(
                workflow,
                List.of(triggerNode, httpNode, aiNode),
                "user-1",
                "exec-http-ai-1",
                TriggerContext.manual("user-1")
        );

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(3, result.nodes().size());

        NodeExecutionResult structResult = result.nodes().get(2);
        assertEquals(ExecutionStatus.SUCCESS, structResult.status());
        Map<String, Object> structured = (Map<String, Object>) structResult.output().get("structured");
        assertNotNull(structured);
        assertEquals("Bob Miller", structured.get("fullName"));
        assertEquals("bob@example.com", structured.get("email"));
    }
}
