package com.adonis.integration.webhook;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Webhook Idempotency Integration Tests (Real MongoDB)")
class WebhookIdempotencyIntegrationTest extends AdonisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Webhook idempotency: duplicate requests with same Idempotency-Key return existing execution, creating only one in Mongo")
    void webhookIdempotency_DuplicateRequest_ReturnsExistingExecution() throws Exception {
        String path = "path-idemp-test";
        String idempotencyKey = "idemp-key-abc-123";

        Workflow workflow = TestDataFactory.createWebhookWorkflow("user-webhook", "Idempotent Flow", path, null);
        workflow = workflowRepository.save(workflow);

        // 1. First request with Idempotency-Key
        MvcResult res1 = mockMvc.perform(post("/api/webhooks/" + path)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"payment.completed\",\"amount\":250}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andReturn();

        JsonNode json1 = objectMapper.readTree(res1.getResponse().getContentAsString());
        String executionId1 = json1.get("executionId").asText();

        // 2. Second request with same Idempotency-Key
        MvcResult res2 = mockMvc.perform(post("/api/webhooks/" + path)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"payment.completed\",\"amount\":250}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(executionId1))
                .andReturn();

        JsonNode json2 = objectMapper.readTree(res2.getResponse().getContentAsString());
        String executionId2 = json2.get("executionId").asText();

        // Must be the same execution ID
        assertEquals(executionId1, executionId2);

        // Verify only ONE execution document exists in MongoDB
        List<WorkflowExecution> executions = executionRepository.findAll();
        assertEquals(1, executions.size(), "Only one execution document must be stored for duplicate idempotency keys");
        assertEquals(idempotencyKey, executions.get(0).getIdempotencyKey());
    }

    @Test
    @DisplayName("Webhook idempotency: concurrent requests with identical idempotency key create only one execution in MongoDB")
    void webhookIdempotency_ConcurrentRequests_OnlyOneExecutionCreated() throws Exception {
        String path = "path-concurrent-idemp";
        String idempotencyKey = "idemp-concurrent-key-999";

        Workflow workflow = TestDataFactory.createWebhookWorkflow("user-webhook", "Concurrent Idemp Flow", path, null);
        Workflow savedWorkflow = workflowRepository.save(workflow);

        int threads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<ExecuteWorkflowResponse>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return executionService.enqueueWebhookExecution(
                        savedWorkflow,
                        idempotencyKey,
                        Map.of("data", "concurrent")
                );
            }));
        }

        startLatch.countDown();

        String expectedExecutionId = null;
        for (Future<ExecuteWorkflowResponse> f : futures) {
            ExecuteWorkflowResponse resp = f.get(5, TimeUnit.SECONDS);
            assertNotNull(resp);
            if (expectedExecutionId == null) {
                expectedExecutionId = resp.executionId();
            } else {
                assertEquals(expectedExecutionId, resp.executionId(), "All concurrent requests must return the same execution ID");
            }
        }

        executor.shutdown();

        // Exactly one execution in MongoDB
        List<WorkflowExecution> executions = executionRepository.findAll();
        assertEquals(1, executions.size(), "MongoDB must store exactly one execution document");
        assertEquals(idempotencyKey, executions.get(0).getIdempotencyKey());
    }
}
