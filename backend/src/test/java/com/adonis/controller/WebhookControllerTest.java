package com.adonis.controller;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.model.*;
import com.adonis.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowRepository workflowRepository;

    @MockBean
    private WorkflowExecutionService executionService;

    private Workflow activeWebhookWorkflow;
    private String validSecret = "whsec_test_secret_1234567890";
    private String webhookPath = "a1b2c3d4e5f67890abcdef";

    @BeforeEach
    void setUp() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig(
                null,
                null,
                webhookPath,
                WorkflowTriggerConfig.hashSecret(validSecret),
                true
        );

        WorkflowNode node = new WorkflowNode("node-1", "trigger", Map.of());
        activeWebhookWorkflow = new Workflow(
                "wf-wh-1",
                "user-1",
                "Webhook Workflow",
                "Test description",
                WorkflowStatus.ACTIVE,
                List.of(node),
                List.of(),
                WorkflowTriggerType.WEBHOOK,
                config,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    void handleWebhook_Success_Returns202AcceptedWithQueuedStatus() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        ExecuteWorkflowResponse queuedResponse = ExecuteWorkflowResponse.queued("exec-wh-1", "wf-wh-1");
        when(executionService.enqueueWebhookExecution(eq(activeWebhookWorkflow), any(), any()))
                .thenReturn(queuedResponse);

        String jsonPayload = objectMapper.writeValueAsString(Map.of("event", "payment.succeeded", "amount", 100));

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Secret", validSecret)
                        .content(jsonPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value("exec-wh-1"))
                .andExpect(jsonPath("$.workflowId").value("wf-wh-1"))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(executionService).enqueueWebhookExecution(eq(activeWebhookWorkflow), isNull(), any());
    }

    @Test
    void handleWebhook_InvalidPath_Returns404NotFound() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath("unknown-path"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/webhooks/unknown-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(executionService);
    }

    @Test
    void handleWebhook_InactiveWorkflow_Returns400BadRequest() throws Exception {
        activeWebhookWorkflow.setStatus(WorkflowStatus.DRAFT);
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(executionService);
    }

    @Test
    void handleWebhook_SecretRequired_MissingSecret_Returns401Unauthorized() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(executionService);
    }

    @Test
    void handleWebhook_SecretRequired_InvalidSecret_Returns401Unauthorized() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .header("X-Webhook-Secret", "wrong-secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(executionService);
    }

    @Test
    void handleWebhook_NoSecretConfigured_AcceptsWithoutSecret() throws Exception {
        // Workflow with no secret
        WorkflowTriggerConfig noSecretConfig = new WorkflowTriggerConfig(null, null, "wh-public-1234", null, false);
        Workflow publicWorkflow = new Workflow(
                "wf-pub",
                "user-1",
                "Public Webhook",
                null,
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of(),
                WorkflowTriggerType.WEBHOOK,
                noSecretConfig,
                Instant.now(),
                Instant.now()
        );

        when(workflowRepository.findByTriggerConfigWebhookPath("wh-public-1234"))
                .thenReturn(Optional.of(publicWorkflow));
        when(executionService.enqueueWebhookExecution(eq(publicWorkflow), any(), any()))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-pub-1", "wf-pub"));

        mockMvc.perform(post("/api/webhooks/wh-public-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\": \"ping\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value("exec-pub-1"));
    }

    @Test
    void handleWebhook_PayloadTooLarge_Returns413() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        // Create large payload > 1MB
        String largeString = "a".repeat(1024 * 1024 + 100);

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .header("X-Webhook-Secret", validSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\": \"" + largeString + "\"}"))
                .andExpect(status().isPayloadTooLarge());

        verifyNoInteractions(executionService);
    }

    @Test
    void handleWebhook_MalformedJsonPayload_Returns400BadRequest() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .header("X-Webhook-Secret", validSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed json here"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(executionService);
    }

    @Test
    void handleWebhook_WithIdempotencyKey_PropagatedToService() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        when(executionService.enqueueWebhookExecution(eq(activeWebhookWorkflow), eq("idem-key-999"), any()))
                .thenReturn(ExecuteWorkflowResponse.queued("exec-idem-1", "wf-wh-1"));

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .header("X-Webhook-Secret", validSecret)
                        .header("Idempotency-Key", "idem-key-999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value("exec-idem-1"));

        verify(executionService).enqueueWebhookExecution(eq(activeWebhookWorkflow), eq("idem-key-999"), any());
    }

    @Test
    void handleWebhook_DuplicateIdempotencyKey_ReturnsExistingExecution() throws Exception {
        when(workflowRepository.findByTriggerConfigWebhookPath(webhookPath))
                .thenReturn(Optional.of(activeWebhookWorkflow));

        // Returns existing execution already in SUCCESS or RUNNING status
        ExecuteWorkflowResponse existingResponse = new ExecuteWorkflowResponse("exec-already-ran", "wf-wh-1", ExecutionStatus.SUCCESS);
        when(executionService.enqueueWebhookExecution(eq(activeWebhookWorkflow), eq("idem-dup-key"), any()))
                .thenReturn(existingResponse);

        mockMvc.perform(post("/api/webhooks/{webhookPath}", webhookPath)
                        .header("X-Webhook-Secret", validSecret)
                        .header("Idempotency-Key", "idem-dup-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"duplicate\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value("exec-already-ran"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
