package com.adonis.integration.execution;

import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.NodeExecutionResult;
import com.adonis.execution.WorkflowExecutionResult;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.LocalMockHttpServer;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AI Structured Output Integration Tests (Real Execution Engine & Schema Validator)")
class AIStructuredOutputIntegrationTest extends AdonisIntegrationTest {

    private static final String PERSON_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer" }
              },
              "required": ["name", "age"]
            }
            """;

    @Test
    @DisplayName("Structured output: valid JSON adheres to schema and completes with SUCCESS")
    void structuredOutput_ValidJson_Succeeds() {
        String validJson = "{\"name\":\"Alice\",\"age\":25}";
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson(validJson, "gpt-4o", 10, 15));

        Workflow workflow = TestDataFactory.createAiStructuredWorkflow("user-ai", "Valid JSON Flow",
                "openai", "gpt-4o", "Generate person", PERSON_SCHEMA);

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-valid-json");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, aiResult.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) aiResult.output().get("parsedJson");
        assertNotNull(parsed);
        assertEquals("Alice", parsed.get("name"));
        assertEquals(25, parsed.get("age"));
    }

    @Test
    @DisplayName("Structured output: markdown-wrapped JSON is stripped, validated, and completes with SUCCESS")
    void structuredOutput_MarkdownJson_StrippedAndSucceeds() {
        String markdownJson = "```json\n{\"name\":\"Alice\",\"age\":25}\n```";
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson(markdownJson, "gpt-4o", 10, 15));

        Workflow workflow = TestDataFactory.createAiStructuredWorkflow("user-ai", "Markdown JSON Flow",
                "openai", "gpt-4o", "Generate person", PERSON_SCHEMA);

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-md-json");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, aiResult.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) aiResult.output().get("parsedJson");
        assertNotNull(parsed);
        assertEquals("Alice", parsed.get("name"));
        assertEquals(25, parsed.get("age"));
    }

    @Test
    @DisplayName("Structured output: malformed JSON fails schema parsing and marks node FAILED")
    void structuredOutput_MalformedJson_Fails() {
        String malformedJson = "{ name: Alice }";
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson(malformedJson, "gpt-4o", 10, 15));

        Workflow workflow = TestDataFactory.createAiStructuredWorkflow("user-ai", "Malformed JSON Flow",
                "openai", "gpt-4o", "Generate person", PERSON_SCHEMA);

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-malformed-json");

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, aiResult.status());
        assertNotNull(aiResult.error());
        assertTrue(aiResult.error().toLowerCase().contains("json") || aiResult.error().toLowerCase().contains("parse"));
    }

    @Test
    @DisplayName("Structured output: missing required property fails schema validation")
    void structuredOutput_MissingRequiredProperty_Fails() {
        // Missing "age" property
        String missingAge = "{\"name\":\"Alice\"}";
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson(missingAge, "gpt-4o", 10, 15));

        Workflow workflow = TestDataFactory.createAiStructuredWorkflow("user-ai", "Schema Missing Prop Flow",
                "openai", "gpt-4o", "Generate person", PERSON_SCHEMA);

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-missing-prop");

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, aiResult.status());
        assertNotNull(aiResult.error());
        assertTrue(aiResult.error().contains("age"), "Error message must report missing required property 'age'");
    }

    @Test
    @DisplayName("Structured output: wrong property type fails schema validation")
    void structuredOutput_WrongPropertyType_Fails() {
        // "age" is string instead of integer
        String wrongType = "{\"name\":\"Alice\",\"age\":\"twenty\"}";
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson(wrongType, "gpt-4o", 10, 15));

        Workflow workflow = TestDataFactory.createAiStructuredWorkflow("user-ai", "Schema Wrong Type Flow",
                "openai", "gpt-4o", "Generate person", PERSON_SCHEMA);

        WorkflowExecutionResult result = engine.execute(workflow, workflow.getNodes(), "user-ai", "exec-wrong-type");

        assertEquals(ExecutionStatus.FAILED, result.status());
        NodeExecutionResult aiResult = result.nodes().stream()
                .filter(n -> "ai_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, aiResult.status());
        assertNotNull(aiResult.error());
        assertTrue(aiResult.error().toLowerCase().contains("integer") || aiResult.error().toLowerCase().contains("type"));
    }
}
