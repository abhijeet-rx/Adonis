package com.adonis.integration.webhook;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowTriggerType;
import com.adonis.queue.QueuedJobMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Webhook Integration Tests (Real Webhook Pipeline with MongoDB & Redis)")
class WebhookIntegrationTest extends AdonisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Webhook pipeline: valid webhook request creates execution, enqueues to Redis, executed by worker to SUCCESS")
    void webhook_ValidRequest_CreatesExecution_EnqueuesToRedis_AndExecutes() throws Exception {
        mockHttpServer.setDefaultHttpResponse(200, "{\"received\":true}");

        String secret = "whsec_super_secret_test_token_123";
        String path = "path-orders-hook";

        Workflow workflow = TestDataFactory.createWebhookWorkflow("user-webhook", "Order Webhook Flow", path, secret);
        workflow = workflowRepository.save(workflow);

        // 1. Submit valid webhook request with secret
        String requestJson = "{\"orderId\":\"ord-999\",\"amount\":150.0}";
        MvcResult mvcResult = mockMvc.perform(post("/api/webhooks/" + path)
                        .header("X-Webhook-Secret", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        String executionId = responseNode.get("executionId").asText();

        // 2. Verify execution record in MongoDB
        WorkflowExecution execution = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionStatus.QUEUED, execution.getStatus());
        assertEquals("WEBHOOK", execution.getTriggerType());
        assertNotNull(execution.getTriggerPayload());
        assertEquals("POST", execution.getTriggerPayload().get("method"));

        // 3. Worker consumes from Redis and executes
        Optional<QueuedJobMessage> msgOpt = queue.poll(Duration.ofSeconds(2));
        assertTrue(msgOpt.isPresent());
        assertEquals(executionId, msgOpt.get().job().executionId());

        boolean processed = worker.processJob(msgOpt.get());
        assertTrue(processed);

        // 4. Verify MongoDB final status is SUCCESS
        WorkflowExecution completed = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());
        assertNotNull(completed.getCompletedAt());
    }

    @Test
    @DisplayName("Webhook security: invalid secret returns 401 Unauthorized")
    void webhook_InvalidSecret_ReturnsUnauthorized() throws Exception {
        String secret = "whsec_correct_secret";
        String path = "path-secure-hook";

        Workflow workflow = TestDataFactory.createWebhookWorkflow("user-webhook", "Secure Flow", path, secret);
        workflowRepository.save(workflow);

        mockMvc.perform(post("/api/webhooks/" + path)
                        .header("X-Webhook-Secret", "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"ping\"}"))
                .andExpect(status().isUnauthorized());

        // Zero executions created
        assertEquals(0, executionRepository.count());
    }

    @Test
    @DisplayName("Webhook security: missing secret returns 401 Unauthorized when secret is configured")
    void webhook_MissingSecret_ReturnsUnauthorized() throws Exception {
        String secret = "whsec_required_secret";
        String path = "path-required-hook";

        Workflow workflow = TestDataFactory.createWebhookWorkflow("user-webhook", "Required Secret Flow", path, secret);
        workflowRepository.save(workflow);

        mockMvc.perform(post("/api/webhooks/" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"ping\"}"))
                .andExpect(status().isUnauthorized());

        assertEquals(0, executionRepository.count());
    }

    @Test
    @DisplayName("Webhook routing: unknown webhook path returns 404 Not Found")
    void webhook_UnknownPath_ReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/webhooks/non-existent-webhook-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Webhook routing: workflow with non-WEBHOOK triggerType returns 404 Not Found")
    void webhook_NonWebhookTriggerType_ReturnsNotFound() throws Exception {
        String path = "path-manual-wf";
        Workflow workflow = TestDataFactory.createHttpWorkflow("user-webhook", "Manual Flow", "http://localhost:8080", "GET");
        workflow.getTriggerConfig().setWebhookPath(path);
        workflow.setTriggerType(WorkflowTriggerType.MANUAL);
        workflowRepository.save(workflow);

        mockMvc.perform(post("/api/webhooks/" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"test\"}"))
                .andExpect(status().isNotFound());
    }
}
