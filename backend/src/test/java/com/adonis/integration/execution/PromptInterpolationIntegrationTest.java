package com.adonis.integration.execution;

import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.WorkflowExecutionResult;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.LocalMockHttpServer;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Prompt Interpolation Integration Tests (Real Workflow Pipeline)")
class PromptInterpolationIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Prompt interpolation: HTTP node output is interpolated into downstream AI prompt (Hello {{http_1.output.body.name}})")
    void promptInterpolation_HttpOutputToAiPrompt_InterpolatesCorrectly() {
        // HTTP mock returns JSON body {"name": "Abhi"}
        mockHttpServer.enqueueHttpResponse(200, "{\"name\":\"Abhi\"}");
        // OpenAI mock returns answer
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson("Greetings Abhi!", "gpt-4o", 10, 10));

        WorkflowNode trigger = TestDataFactory.createTriggerNode("trigger_1");
        WorkflowNode http = TestDataFactory.createHttpNode("http_1", mockHttpServer.getHttpEndpointUrl(), "GET", Map.of(), null);
        WorkflowNode ai = TestDataFactory.createAiTextNode("ai_1", "openai", "gpt-4o",
                "You are friendly", "Hello {{http_1.output.body.name}}", 0.5, 50);

        WorkflowEdge edge1 = TestDataFactory.createEdge("trigger_1", "http_1");
        WorkflowEdge edge2 = TestDataFactory.createEdge("http_1", "ai_1");

        Workflow workflow = TestDataFactory.createWorkflow(null, "user-interp", "Interp Flow",
                List.of(trigger, http, ai), List.of(edge1, edge2));

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-interp", "exec-interp-1");

        assertEquals(ExecutionStatus.SUCCESS, result.status());

        // Inspect recorded OpenAI request sent to mock server
        assertEquals(1, mockHttpServer.getRecordedOpenAiRequests().size());
        LocalMockHttpServer.RecordedHttpRequest req = mockHttpServer.getRecordedOpenAiRequests().get(0);
        assertTrue(req.body().contains("Hello Abhi"), "Interpolated prompt must contain 'Hello Abhi', got: " + req.body());
    }

    @Test
    @DisplayName("Prompt interpolation: nested object values, trigger input, and missing values handled gracefully")
    void promptInterpolation_NestedAndMissingValues_HandledSafely() {
        mockHttpServer.enqueueHttpResponse(200, "{\"user\":{\"profile\":{\"city\":\"Tokyo\"}}}");
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson("City identified", "gpt-4o", 10, 10));

        WorkflowNode trigger = TestDataFactory.createTriggerNode("trigger_1");
        trigger.getData().put("accountType", "Enterprise");

        WorkflowNode http = TestDataFactory.createHttpNode("http_1", mockHttpServer.getHttpEndpointUrl(), "GET", Map.of(), null);
        // Uses: nested city, trigger property, and a non-existent property
        WorkflowNode ai = TestDataFactory.createAiTextNode("ai_1", "openai", "gpt-4o", null,
                "City: {{http_1.output.body.user.profile.city}}, Plan: {{trigger_1.output.accountType}}, Extra: {{http_1.output.body.nonexistent}}",
                0.0, 50);

        WorkflowEdge edge1 = TestDataFactory.createEdge("trigger_1", "http_1");
        WorkflowEdge edge2 = TestDataFactory.createEdge("http_1", "ai_1");

        Workflow workflow = TestDataFactory.createWorkflow(null, "user-interp", "Nested Flow",
                List.of(trigger, http, ai), List.of(edge1, edge2));

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-interp", "exec-interp-nested");

        assertEquals(ExecutionStatus.SUCCESS, result.status());

        LocalMockHttpServer.RecordedHttpRequest req = mockHttpServer.getRecordedOpenAiRequests().get(0);
        assertTrue(req.body().contains("City: Tokyo"), "Nested property must interpolate");
        assertTrue(req.body().contains("Plan: Enterprise"), "Trigger property must interpolate");
    }
}
