package com.adonis.integration.e2e;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.LocalMockHttpServer;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.*;
import com.adonis.queue.QueuedJobMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("End-to-End Flagship Integration Test (Webhook -> HTTP -> AI Structured Output -> Retry -> Redis -> Worker -> Mongo)")
class EndToEndWorkflowIntegrationTest extends AdonisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String FRAUD_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "orderId": { "type": "string" },
                "fraudScore": { "type": "number" },
                "approved": { "type": "boolean" }
              },
              "required": ["orderId", "fraudScore", "approved"]
            }
            """;

    @Test
    @DisplayName("Flagship E2E: Webhook -> HTTP Node (200) -> AI Node (429 then 200 Retry) -> Schema Validation -> Redis Stream -> Worker -> MongoDB Execution History")
    void flagshipEndToEndWorkflowPipeline_ExecutesSuccessfullyWithFullHistory() throws Exception {
        String webhookPath = "orders-fraud-check";
        String webhookSecret = "whsec_flagship_test_secret_123";

        // 1. Configure Local Mock HTTP Server endpoints:
        // HTTP Node endpoint returns order details
        mockHttpServer.enqueueHttpResponse(200, "{\"orderId\":\"ORD-7890\",\"customer\":\"Abhi\",\"amount\":500.0}");

        // AI Node endpoint:
        // Attempt 1 fails with 429 Too Many Requests (transient retryable)
        mockHttpServer.enqueueOpenAiResponse(429, "{\"error\":{\"message\":\"Rate limit exceeded\",\"type\":\"requests\"}}");
        // Attempt 2 succeeds with structured JSON adhering to FRAUD_SCHEMA
        String validAiJson = "{\"orderId\":\"ORD-7890\",\"fraudScore\":0.02,\"approved\":true}";
        mockHttpServer.enqueueOpenAiResponse(200,
                LocalMockHttpServer.buildOpenAiSuccessJson(validAiJson, "gpt-4o", 25, 35));

        // 2. Build the Multi-Node Workflow with Retry on AI Node:
        // Trigger (Webhook) -> HTTP Node -> AI Node
        WorkflowNode triggerNode = TestDataFactory.createTriggerNode("trigger_1");
        WorkflowNode httpNode = TestDataFactory.createHttpNode("http_1", mockHttpServer.getHttpEndpointUrl(), "POST",
                Map.of("Content-Type", "application/json"), Map.of("request", "getOrderDetails"));

        Map<String, Object> aiData = Map.of(
                "provider", "openai",
                "model", "gpt-4o",
                "systemPrompt", "You are an automated fraud assessment system. Output strict JSON.",
                "userPrompt", "Analyze order {{http_1.output.body.orderId}} for customer {{http_1.output.body.customer}}",
                "jsonSchema", FRAUD_SCHEMA,
                "temperature", 0.0,
                "maxTokens", 150,
                "retry", Map.of(
                        "enabled", true,
                        "maxRetries", 2,
                        "initialBackoffMs", 50L,
                        "backoffMultiplier", 1.5,
                        "maxBackoffMs", 200L
                )
        );
        WorkflowNode aiNode = new WorkflowNode("ai_1", "ai_structured_output", aiData);

        WorkflowEdge edge1 = TestDataFactory.createEdge("trigger_1", "http_1");
        WorkflowEdge edge2 = TestDataFactory.createEdge("http_1", "ai_1");

        String secretHash = WorkflowTriggerConfig.hashSecret(webhookSecret);
        WorkflowTriggerConfig triggerConfig = new WorkflowTriggerConfig(null, null, webhookPath, secretHash, true);

        Workflow workflow = new Workflow(
                null,
                "user-e2e",
                "Flagship Fraud Verification Flow",
                "E2E Webhook-HTTP-AI Pipeline",
                WorkflowStatus.ACTIVE,
                List.of(triggerNode, httpNode, aiNode),
                List.of(edge1, edge2),
                WorkflowTriggerType.WEBHOOK,
                triggerConfig,
                java.time.Instant.now(),
                java.time.Instant.now()
        );
        workflow = workflowRepository.save(workflow);

        // 3. Trigger the workflow via Webhook HTTP endpoint
        String incomingPayload = "{\"source\":\"ecommerce-store\",\"timestamp\":1727784000}";
        MvcResult mvcResult = mockMvc.perform(post("/api/webhooks/" + webhookPath)
                        .header("X-Webhook-Secret", webhookSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incomingPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        String executionId = responseJson.get("executionId").asText();

        // 4. Verify job is present in Redis Stream
        assertEquals(1, queue.size());

        // 5. Worker consumes job from Redis Stream
        Optional<QueuedJobMessage> msgOpt = queue.poll(Duration.ofSeconds(2));
        assertTrue(msgOpt.isPresent());
        QueuedJobMessage msg = msgOpt.get();
        assertEquals(executionId, msg.job().executionId());

        // 6. Worker processes the execution (runs Engine: HTTP -> AI retry -> success -> persistence -> XACK)
        boolean processed = worker.processJob(msg);
        assertTrue(processed, "ExecutionWorker must process the job through the engine successfully");

        // 7. Verify Redis message was acknowledged (removed from PEL)
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(TEST_STREAM_KEY, TEST_CONSUMER_GROUP, Range.unbounded(), 10);
        assertTrue(pending == null || pending.isEmpty(), "Processed message must be acknowledged and removed from PEL");

        // 8. Verify the complete MongoDB Execution History
        WorkflowExecution finalExecution = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, finalExecution.getStatus());
        assertEquals("WEBHOOK", finalExecution.getTriggerType());
        assertNotNull(finalExecution.getStartedAt());
        assertNotNull(finalExecution.getCompletedAt());
        assertTrue(finalExecution.getDurationMs() >= 0);
        assertNull(finalExecution.getError());

        // Verify trigger payload preserved
        assertNotNull(finalExecution.getTriggerPayload());
        assertEquals("/api/webhooks/" + webhookPath, finalExecution.getTriggerPayload().get("path"));

        // Verify NodeExecutions list contains all 3 nodes
        List<NodeExecution> nodeExecs = finalExecution.getNodeExecutions();
        assertEquals(3, nodeExecs.size());

        // Node 1: Trigger
        NodeExecution n1 = nodeExecs.get(0);
        assertEquals("trigger_1", n1.getNodeId());
        assertEquals(ExecutionStatus.SUCCESS, n1.getStatus());

        // Node 2: HTTP Node
        NodeExecution n2 = nodeExecs.get(1);
        assertEquals("http_1", n2.getNodeId());
        assertEquals(ExecutionStatus.SUCCESS, n2.getStatus());
        assertEquals(200, n2.getOutput().get("statusCode"));

        // Node 3: AI Node (Structured Output with Retry)
        NodeExecution n3 = nodeExecs.get(2);
        assertEquals("ai_1", n3.getNodeId());
        assertEquals(ExecutionStatus.SUCCESS, n3.getStatus());
        assertEquals(1, n3.getRetryCount(), "AI node must have retryCount=1 due to 429 on first attempt");

        // Verify AI parsed structured output
        @SuppressWarnings("unchecked")
        Map<String, Object> parsedJson = (Map<String, Object>) n3.getOutput().get("parsedJson");
        assertNotNull(parsedJson);
        assertEquals("ORD-7890", parsedJson.get("orderId"));
        assertEquals(true, parsedJson.get("approved"));
        assertEquals(0.02, ((Number) parsedJson.get("fraudScore")).doubleValue(), 0.001);

        // Verify Attempt History in MongoDB: Attempt 1 (429 FAILED), Attempt 2 (SUCCESS)
        List<NodeExecutionAttempt> attempts = n3.getAttempts();
        assertNotNull(attempts);
        assertEquals(2, attempts.size());

        NodeExecutionAttempt a1 = attempts.get(0);
        assertEquals(1, a1.getAttemptNumber());
        assertEquals(ExecutionStatus.FAILED, a1.getStatus());
        assertTrue(a1.getError().contains("429"));

        NodeExecutionAttempt a2 = attempts.get(1);
        assertEquals(2, a2.getAttemptNumber());
        assertEquals(ExecutionStatus.SUCCESS, a2.getStatus());
        assertNull(a2.getError());

        // Verify prompt interpolation received by fake server
        List<LocalMockHttpServer.RecordedHttpRequest> openAiRequests = mockHttpServer.getRecordedOpenAiRequests();
        assertEquals(2, openAiRequests.size(), "Two attempts should have been made to OpenAI");
        assertTrue(openAiRequests.get(0).body().contains("Analyze order ORD-7890 for customer Abhi"));
        assertTrue(openAiRequests.get(1).body().contains("Analyze order ORD-7890 for customer Abhi"));
    }
}
