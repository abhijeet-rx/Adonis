package com.adonis.integration.support;

import com.adonis.model.*;

import java.time.Instant;
import java.util.*;

/**
 * Reusable test data factory for integration tests.
 * Simplifies construction of Users, Workflows, Nodes, Edges, Executions, and Retries.
 */
public final class TestDataFactory {

    private TestDataFactory() {
    }

    public static User createUser(String name, String email, String passwordHash) {
        return User.create(name, email, passwordHash);
    }

    public static User createDefaultUser() {
        return createUser("Test User", "test-" + UUID.randomUUID() + "@example.com", "hashed_secret_password");
    }

    public static WorkflowNode createTriggerNode(String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", "Manual Trigger");
        return new WorkflowNode(id, "trigger", data, new WorkflowNodePosition(0.0, 0.0));
    }

    public static WorkflowNode createHttpNode(String id, String url, String method, Map<String, String> headers, Object body) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("method", method != null ? method : "GET");
        if (headers != null && !headers.isEmpty()) {
            data.put("headers", headers);
        }
        if (body != null) {
            data.put("body", body);
        }
        return new WorkflowNode(id, "http", data, new WorkflowNodePosition(100.0, 0.0));
    }

    public static WorkflowNode createAiTextNode(String id, String provider, String model, String systemPrompt, String userPrompt, Double temperature, Integer maxTokens) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", provider != null ? provider : "openai");
        data.put("model", model != null ? model : "gpt-4o");
        if (systemPrompt != null) {
            data.put("systemPrompt", systemPrompt);
        }
        data.put("userPrompt", userPrompt != null ? userPrompt : "Hello");
        if (temperature != null) {
            data.put("temperature", temperature);
        }
        if (maxTokens != null) {
            data.put("maxTokens", maxTokens);
        }
        return new WorkflowNode(id, "ai_text_generation", data, new WorkflowNodePosition(200.0, 0.0));
    }

    public static WorkflowNode createAiStructuredOutputNode(String id, String provider, String model, String systemPrompt, String userPrompt, String jsonSchema, Double temperature, Integer maxTokens) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", provider != null ? provider : "openai");
        data.put("model", model != null ? model : "gpt-4o");
        if (systemPrompt != null) {
            data.put("systemPrompt", systemPrompt);
        }
        data.put("userPrompt", userPrompt != null ? userPrompt : "Generate structured JSON");
        data.put("jsonSchema", jsonSchema != null ? jsonSchema : "{\"type\":\"object\"}");
        if (temperature != null) {
            data.put("temperature", temperature);
        }
        if (maxTokens != null) {
            data.put("maxTokens", maxTokens);
        }
        return new WorkflowNode(id, "ai_structured_output", data, new WorkflowNodePosition(200.0, 0.0));
    }

    public static WorkflowEdge createEdge(String source, String target) {
        return new WorkflowEdge("edge-" + source + "-" + target, source, target);
    }

    public static Workflow createWorkflow(String id, String userId, String name, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Instant now = Instant.now();
        return new Workflow(
                id,
                userId,
                name,
                "Integration test workflow",
                WorkflowStatus.ACTIVE,
                nodes != null ? nodes : List.of(),
                edges != null ? edges : List.of(),
                WorkflowTriggerType.MANUAL,
                new WorkflowTriggerConfig(),
                now,
                now
        );
    }

    public static Workflow createHttpWorkflow(String userId, String name, String url, String method) {
        WorkflowNode trigger = createTriggerNode("trigger_1");
        WorkflowNode http = createHttpNode("http_1", url, method, Map.of("Accept", "application/json"), null);
        WorkflowEdge edge = createEdge("trigger_1", "http_1");
        return createWorkflow(null, userId, name, List.of(trigger, http), List.of(edge));
    }

    public static Workflow createAiTextWorkflow(String userId, String name, String provider, String model, String userPrompt) {
        WorkflowNode trigger = createTriggerNode("trigger_1");
        WorkflowNode ai = createAiTextNode("ai_1", provider, model, "You are a helpful assistant", userPrompt, 0.7, 100);
        WorkflowEdge edge = createEdge("trigger_1", "ai_1");
        return createWorkflow(null, userId, name, List.of(trigger, ai), List.of(edge));
    }

    public static Workflow createAiStructuredWorkflow(String userId, String name, String provider, String model, String userPrompt, String schema) {
        WorkflowNode trigger = createTriggerNode("trigger_1");
        WorkflowNode ai = createAiStructuredOutputNode("ai_1", provider, model, "Output valid JSON", userPrompt, schema, 0.2, 200);
        WorkflowEdge edge = createEdge("trigger_1", "ai_1");
        return createWorkflow(null, userId, name, List.of(trigger, ai), List.of(edge));
    }

    public static Workflow createScheduledWorkflow(String userId, String name, String cronExpression, String timezone) {
        WorkflowNode trigger = createTriggerNode("trigger_1");
        WorkflowNode http = createHttpNode("http_1", "http://localhost:8080/health", "GET", Map.of(), null);
        WorkflowEdge edge = createEdge("trigger_1", "http_1");

        WorkflowTriggerConfig config = new WorkflowTriggerConfig(cronExpression, timezone, null, null, false);
        Instant now = Instant.now();
        return new Workflow(
                null,
                userId,
                name,
                "Scheduled test workflow",
                WorkflowStatus.ACTIVE,
                List.of(trigger, http),
                List.of(edge),
                WorkflowTriggerType.SCHEDULE,
                config,
                now,
                now
        );
    }

    public static Workflow createWebhookWorkflow(String userId, String name, String webhookPath, String secret) {
        WorkflowNode trigger = createTriggerNode("trigger_1");
        WorkflowNode http = createHttpNode("http_1", "http://localhost:8080/health", "GET", Map.of(), null);
        WorkflowEdge edge = createEdge("trigger_1", "http_1");

        String secretHash = secret != null ? WorkflowTriggerConfig.hashSecret(secret) : null;
        WorkflowTriggerConfig config = new WorkflowTriggerConfig(null, null, webhookPath, secretHash, secret != null);
        Instant now = Instant.now();
        return new Workflow(
                null,
                userId,
                name,
                "Webhook test workflow",
                WorkflowStatus.ACTIVE,
                List.of(trigger, http),
                List.of(edge),
                WorkflowTriggerType.WEBHOOK,
                config,
                now,
                now
        );
    }

    public static Workflow createPipelineWorkflow(
            String userId,
            String name,
            String webhookPath,
            String webhookSecret,
            String httpUrl,
            String aiUserPrompt,
            String aiJsonSchema) {
        WorkflowNode trigger = createTriggerNode("trigger_1");
        WorkflowNode http = createHttpNode("http_1", httpUrl, "POST", Map.of("Content-Type", "application/json"), Map.of("query", "data"));
        WorkflowNode ai = createAiStructuredOutputNode("ai_1", "openai", "gpt-4o", "Analyze input and return JSON", aiUserPrompt, aiJsonSchema, 0.0, 150);

        WorkflowEdge edge1 = createEdge("trigger_1", "http_1");
        WorkflowEdge edge2 = createEdge("http_1", "ai_1");

        String secretHash = webhookSecret != null ? WorkflowTriggerConfig.hashSecret(webhookSecret) : null;
        WorkflowTriggerConfig config = new WorkflowTriggerConfig(null, null, webhookPath, secretHash, webhookSecret != null);
        Instant now = Instant.now();
        return new Workflow(
                null,
                userId,
                name,
                "E2E Webhook-HTTP-AI pipeline workflow",
                WorkflowStatus.ACTIVE,
                List.of(trigger, http, ai),
                List.of(edge1, edge2),
                WorkflowTriggerType.WEBHOOK,
                config,
                now,
                now
        );
    }

    public static WorkflowExecution createQueuedExecution(String workflowId, String userId, String triggerType) {
        return WorkflowExecution.queued(workflowId, userId, triggerType != null ? triggerType : "MANUAL");
    }

    public static RetryConfig createRetryConfig(int maxRetries, long backoffMs, double multiplier) {
        return RetryConfig.enabled(maxRetries, backoffMs, multiplier, 30000L);
    }
}
