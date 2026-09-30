package com.adonis.service;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WebhookRegenerateResponse;
import com.adonis.dto.WorkflowResponse;
import com.adonis.dto.WorkflowTriggerConfigRequest;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.exception.WorkflowValidationException;
import com.adonis.execution.WorkflowTriggerValidator;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowNodePosition;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerConfig;
import com.adonis.model.WorkflowTriggerType;
import com.adonis.repository.ScheduledOccurrenceRepository;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ScheduledOccurrenceRepository scheduledOccurrenceRepository;

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(
                workflowRepository,
                new WorkflowTriggerValidator(),
                scheduledOccurrenceRepository
        );
    }

    @Test
    void createWorkflow_ShouldPopulateUserAndSystemFields() {
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                "Data Sync",
                "Syncs data periodically",
                WorkflowStatus.ACTIVE,
                List.of(new WorkflowNode("node-1", "trigger", Map.of("interval", "1h"))),
                List.of(new WorkflowEdge("edge-1", "node-1", "node-2"))
        );

        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow w = invocation.getArgument(0);
            w.setId("wf-generated-id");
            return w;
        });

        WorkflowResponse response = workflowService.createWorkflow("user-100", request);

        assertNotNull(response);
        assertEquals("wf-generated-id", response.id());
        assertEquals("user-100", response.userId());
        assertEquals("Data Sync", response.name());
        assertEquals("Syncs data periodically", response.description());
        assertEquals(WorkflowStatus.ACTIVE, response.status());
        assertEquals(1, response.nodes().size());
        assertEquals(1, response.edges().size());
        assertNotNull(response.createdAt());
        assertNotNull(response.updatedAt());

        ArgumentCaptor<Workflow> captor = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowRepository).save(captor.capture());
        Workflow saved = captor.getValue();
        assertEquals("user-100", saved.getUserId());
        assertEquals("Data Sync", saved.getName());
    }

    @Test
    void createWorkflow_ShouldDefaultDraftStatusAndEmptyCollectionsWhenOmitted() {
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                "Minimal Workflow",
                null,
                null,
                null,
                null
        );

        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow w = invocation.getArgument(0);
            w.setId("wf-minimal");
            return w;
        });

        WorkflowResponse response = workflowService.createWorkflow("user-100", request);

        assertEquals(WorkflowStatus.DRAFT, response.status());
        assertNotNull(response.nodes());
        assertTrue(response.nodes().isEmpty());
        assertNotNull(response.edges());
        assertTrue(response.edges().isEmpty());
    }

    @Test
    void listWorkflows_ShouldReturnOnlyUserWorkflows() {
        Workflow wf1 = new Workflow("wf-1", "user-100", "WF1", "Desc", WorkflowStatus.DRAFT, List.of(), List.of(), Instant.now(), Instant.now());
        Workflow wf2 = new Workflow("wf-2", "user-100", "WF2", "Desc", WorkflowStatus.ACTIVE, List.of(), List.of(), Instant.now(), Instant.now());

        when(workflowRepository.findByUserId("user-100")).thenReturn(List.of(wf1, wf2));

        List<WorkflowResponse> list = workflowService.listWorkflows("user-100");

        assertEquals(2, list.size());
        assertEquals("wf-1", list.get(0).id());
        assertEquals("wf-2", list.get(1).id());
        verify(workflowRepository).findByUserId("user-100");
    }

    @Test
    void getWorkflow_WhenOwnedByUser_ShouldReturnWorkflow() {
        Instant now = Instant.now();
        Workflow wf = new Workflow("wf-1", "user-100", "WF1", "Desc", WorkflowStatus.DRAFT, List.of(), List.of(), now, now);

        when(workflowRepository.findByIdAndUserId("wf-1", "user-100")).thenReturn(Optional.of(wf));

        WorkflowResponse response = workflowService.getWorkflow("wf-1", "user-100");

        assertEquals("wf-1", response.id());
        assertEquals("WF1", response.name());
        assertEquals("user-100", response.userId());
    }

    @Test
    void getWorkflow_WhenNonexistent_ShouldThrowWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("nonexistent", "user-100")).thenReturn(Optional.empty());

        assertThrows(WorkflowNotFoundException.class, () -> workflowService.getWorkflow("nonexistent", "user-100"));
    }

    @Test
    void getWorkflow_WhenBelongsToAnotherUser_ShouldThrowWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-user-a", "user-B")).thenReturn(Optional.empty());

        assertThrows(WorkflowNotFoundException.class, () -> workflowService.getWorkflow("wf-user-a", "user-B"));
    }

    @Test
    void updateWorkflow_ShouldUpdateMutableFieldsAndChangeUpdatedAtWhilePreservingCreatedAtAndUserId() {
        Instant originalCreatedAt = Instant.now().minusSeconds(3600);
        Instant originalUpdatedAt = Instant.now().minusSeconds(1800);
        Workflow existing = new Workflow("wf-1", "user-100", "Old Name", "Old Desc",
                WorkflowStatus.DRAFT, List.of(), List.of(), originalCreatedAt, originalUpdatedAt);

        when(workflowRepository.findByIdAndUserId("wf-1", "user-100")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWorkflowRequest updateRequest = new UpdateWorkflowRequest(
                "Updated Name",
                "Updated Desc",
                WorkflowStatus.ACTIVE,
                List.of(new WorkflowNode("node-new", "transform", Map.of())),
                List.of(new WorkflowEdge("edge-new", "node-new", "node-target"))
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-100", updateRequest);

        assertEquals("wf-1", response.id());
        assertEquals("user-100", response.userId());
        assertEquals("Updated Name", response.name());
        assertEquals("Updated Desc", response.description());
        assertEquals(WorkflowStatus.ACTIVE, response.status());
        assertEquals(1, response.nodes().size());
        assertEquals(1, response.edges().size());
        assertEquals(originalCreatedAt, response.createdAt());
        assertNotEquals(originalUpdatedAt, response.updatedAt());
        assertTrue(response.updatedAt().isAfter(originalUpdatedAt));

        verify(workflowRepository).save(existing);
    }

    @Test
    void updateWorkflow_ShouldUpdateNodesWithPositionAndEdgesWithHandles() {
        Workflow existing = new Workflow("wf-visual", "user-100", "Old Name", "Old Desc",
                WorkflowStatus.DRAFT, List.of(), List.of(), Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-visual", "user-100")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowNode node = new WorkflowNode(
                "node-flow-1",
                "httpRequest",
                Map.of("label", "Call Service"),
                new WorkflowNodePosition(250.75, 350.125)
        );
        WorkflowEdge edge = new WorkflowEdge("edge-flow-1", "node-flow-1", "node-flow-2", "output", "input");

        UpdateWorkflowRequest updateRequest = new UpdateWorkflowRequest(
                "Visual Canvas Workflow",
                "Contains positions and handles",
                WorkflowStatus.ACTIVE,
                List.of(node),
                List.of(edge)
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-visual", "user-100", updateRequest);

        assertEquals("Visual Canvas Workflow", response.name());
        assertEquals(1, response.nodes().size());
        assertNotNull(response.nodes().get(0).getPosition());
        assertEquals(250.75, response.nodes().get(0).getPosition().getX());
        assertEquals(350.125, response.nodes().get(0).getPosition().getY());
        assertEquals(1, response.edges().size());
        assertEquals("output", response.edges().get(0).getSourceHandle());
        assertEquals("input", response.edges().get(0).getTargetHandle());
    }

    @Test
    void updateWorkflow_WhenBelongsToAnotherUser_ShouldThrowWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-1", "user-B")).thenReturn(Optional.empty());

        UpdateWorkflowRequest request = new UpdateWorkflowRequest("New Name", "New Desc", WorkflowStatus.ACTIVE, List.of(), List.of());

        assertThrows(WorkflowNotFoundException.class, () -> workflowService.updateWorkflow("wf-1", "user-B", request));
        verify(workflowRepository, never()).save(any());
    }

    // ==========================================
    // Phase 8.1 FIX #1 — Webhook Path Uniqueness
    // ==========================================

    @Test
    void createWorkflow_WithDuplicateWebhookPath_ThrowsValidationException() {
        String existingPath = "shared-capability-path-12345678";
        Workflow existingWf = new Workflow("wf-other", "user-2", "Other WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), Instant.now(), Instant.now());

        when(workflowRepository.findByTriggerConfigWebhookPath(existingPath)).thenReturn(Optional.of(existingWf));

        CreateWorkflowRequest request = new CreateWorkflowRequest(
                "New Webhook WF",
                "Desc",
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of(),
                WorkflowTriggerType.WEBHOOK,
                new WorkflowTriggerConfigRequest(null, null, existingPath, null)
        );

        WorkflowValidationException ex = assertThrows(WorkflowValidationException.class,
                () -> workflowService.createWorkflow("user-1", request));
        assertTrue(ex.getMessage().contains("Webhook path is already in use"));
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void createWorkflow_WhenConcurrentDuplicateWebhookPathHitsIndex_ThrowsCleanException() {
        when(workflowRepository.findByTriggerConfigWebhookPath(anyString())).thenReturn(Optional.empty());
        when(workflowRepository.save(any(Workflow.class))).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        CreateWorkflowRequest request = new CreateWorkflowRequest(
                "New Webhook WF",
                "Desc",
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of(),
                WorkflowTriggerType.WEBHOOK,
                new WorkflowTriggerConfigRequest(null, null, "valid-path-12345", null)
        );

        WorkflowValidationException ex = assertThrows(WorkflowValidationException.class,
                () -> workflowService.createWorkflow("user-1", request));
        assertTrue(ex.getMessage().contains("Webhook path is already in use"));
    }

    @Test
    void updateWorkflow_UpdatingToDuplicateWebhookPath_ThrowsValidationException() {
        Workflow existing = new Workflow("wf-1", "user-1", "My WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), Instant.now(), Instant.now());
        Workflow other = new Workflow("wf-2", "user-2", "Other WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.findByTriggerConfigWebhookPath("colliding-path-123")).thenReturn(Optional.of(other));

        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "My WF",
                null,
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of(),
                WorkflowTriggerType.WEBHOOK,
                new WorkflowTriggerConfigRequest(null, null, "colliding-path-123", null)
        );

        WorkflowValidationException ex = assertThrows(WorkflowValidationException.class,
                () -> workflowService.updateWorkflow("wf-1", "user-1", request));
        assertTrue(ex.getMessage().contains("Webhook path is already in use"));
    }

    // ==========================================
    // Phase 8.1 FIX #3 & #4 — Lifecycle Transitions & Schedule Invalidation
    // ==========================================

    @Test
    void updateWorkflow_Transition_ManualToSchedule_SetsScheduleAndResetsFireTimes() {
        Workflow existing = new Workflow("wf-1", "user-1", "My WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.MANUAL, new WorkflowTriggerConfig(), Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "My WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.SCHEDULE,
                new WorkflowTriggerConfigRequest("0 */5 * * * *", "UTC", null, null)
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-1", request);

        assertEquals(WorkflowTriggerType.SCHEDULE, response.triggerType());
        assertNotNull(response.triggerConfig());
        assertEquals("0 */5 * * * *", response.triggerConfig().cronExpression());
        assertEquals("UTC", response.triggerConfig().timezone());
        assertNull(response.triggerConfig().nextFireTime());
        assertNull(response.triggerConfig().lastScheduledFireTime());
        assertNull(response.triggerConfig().webhookPath());
    }

    @Test
    void updateWorkflow_Transition_ScheduleToManual_ClearsAllScheduleFields() {
        WorkflowTriggerConfig scheduleConfig = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        scheduleConfig.setNextFireTime(Instant.now().plusSeconds(300));
        scheduleConfig.setLastScheduledFireTime(Instant.now().minusSeconds(300));

        Workflow existing = new Workflow("wf-1", "user-1", "My WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, scheduleConfig, Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "My WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.MANUAL,
                null
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-1", request);

        assertEquals(WorkflowTriggerType.MANUAL, response.triggerType());
        assertNull(response.triggerConfig().cronExpression());
        assertNull(response.triggerConfig().nextFireTime());
        assertNull(response.triggerConfig().lastScheduledFireTime());
        assertNull(response.triggerConfig().webhookPath());
    }

    @Test
    void updateWorkflow_Transition_ScheduleToWebhook_ClearsScheduleAndGeneratesWebhook() {
        WorkflowTriggerConfig scheduleConfig = new WorkflowTriggerConfig("0 */5 * * * *", "UTC", null, null, false);
        scheduleConfig.setNextFireTime(Instant.now().plusSeconds(300));

        Workflow existing = new Workflow("wf-1", "user-1", "My WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, scheduleConfig, Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "My WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.WEBHOOK,
                null
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-1", request);

        assertEquals(WorkflowTriggerType.WEBHOOK, response.triggerType());
        assertNull(response.triggerConfig().cronExpression());
        assertNull(response.triggerConfig().nextFireTime());
        assertNotNull(response.triggerConfig().webhookPath());
        assertEquals(64, response.triggerConfig().webhookPath().length());
    }

    @Test
    void updateWorkflow_Transition_WebhookToManual_ClearsWebhookState() {
        WorkflowTriggerConfig webhookConfig = new WorkflowTriggerConfig(null, null, "wh-path-12345678", "hash", true);
        Workflow existing = new Workflow("wf-1", "user-1", "My WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.WEBHOOK, webhookConfig, Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "My WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.MANUAL,
                null
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-1", request);

        assertEquals(WorkflowTriggerType.MANUAL, response.triggerType());
        assertNull(response.triggerConfig().webhookPath());
        assertFalse(response.triggerConfig().hasSecret());
    }

    @Test
    void updateWorkflow_Transition_WebhookToSchedule_ClearsWebhookAndInitializesSchedule() {
        WorkflowTriggerConfig webhookConfig = new WorkflowTriggerConfig(null, null, "wh-path-12345678", "hash", true);
        Workflow existing = new Workflow("wf-1", "user-1", "My WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.WEBHOOK, webhookConfig, Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "My WF", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.SCHEDULE,
                new WorkflowTriggerConfigRequest("0 0 9 * * *", "Asia/Kolkata", null, null)
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-1", request);

        assertEquals(WorkflowTriggerType.SCHEDULE, response.triggerType());
        assertNull(response.triggerConfig().webhookPath());
        assertFalse(response.triggerConfig().hasSecret());
        assertEquals("0 0 9 * * *", response.triggerConfig().cronExpression());
        assertEquals("Asia/Kolkata", response.triggerConfig().timezone());
        assertNull(response.triggerConfig().nextFireTime());
    }

    // Fix #4 Tests A, B, C, D
    @Test
    void updateWorkflow_ScheduleChange_CronExpressionChanged_ResetsNextFireTime_TestA() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 9 * * *", "Asia/Kolkata", null, null, false);
        config.setNextFireTime(Instant.parse("2026-10-01T03:30:00Z"));
        config.setLastScheduledFireTime(Instant.parse("2026-09-30T03:30:00Z"));

        Workflow existing = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Change cron to 18:00
        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "WF 1", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.SCHEDULE,
                new WorkflowTriggerConfigRequest("0 0 18 * * *", "Asia/Kolkata", null, null)
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-1", request);

        assertEquals("0 0 18 * * *", response.triggerConfig().cronExpression());
        assertNull(response.triggerConfig().nextFireTime(), "Old nextFireTime must be cleared on cron change");
        assertNull(response.triggerConfig().lastScheduledFireTime(), "lastScheduledFireTime must be cleared");
    }

    @Test
    void updateWorkflow_ScheduleChange_TimezoneChanged_ResetsNextFireTime_TestB() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig("0 0 9 * * *", "UTC", null, null, false);
        config.setNextFireTime(Instant.parse("2026-10-01T09:00:00Z"));

        Workflow existing = new Workflow("wf-1", "user-1", "WF 1", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.SCHEDULE, config, Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(existing));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Change timezone from UTC to Asia/Kolkata
        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "WF 1", null, WorkflowStatus.ACTIVE, List.of(), List.of(),
                WorkflowTriggerType.SCHEDULE,
                new WorkflowTriggerConfigRequest("0 0 9 * * *", "Asia/Kolkata", null, null)
        );

        WorkflowResponse response = workflowService.updateWorkflow("wf-1", "user-1", request);

        assertEquals("Asia/Kolkata", response.triggerConfig().timezone());
        assertNull(response.triggerConfig().nextFireTime(), "nextFireTime must be recalculated when timezone changes");
    }

    // ==========================================
    // Phase 8.1 FIX #8 — Clean Scheduled Occurrences When Workflow is Deleted
    // ==========================================

    @Test
    void deleteWorkflow_WhenOwnedByUser_ShouldDeleteWorkflowAndCleanScheduledOccurrences() {
        Workflow workflow = new Workflow("wf-1", "user-100", "WF 1", "Desc", WorkflowStatus.ACTIVE,
                List.of(), List.of(), Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-1", "user-100")).thenReturn(Optional.of(workflow));
        when(workflowRepository.deleteByIdAndUserId("wf-1", "user-100")).thenReturn(1L);

        assertDoesNotThrow(() -> workflowService.deleteWorkflow("wf-1", "user-100"));

        verify(scheduledOccurrenceRepository).deleteByWorkflowId("wf-1");
        verify(workflowRepository).deleteByIdAndUserId("wf-1", "user-100");
    }

    @Test
    void deleteWorkflow_WhenBelongsToAnotherUser_ShouldThrowWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-1", "user-B")).thenReturn(Optional.empty());

        assertThrows(WorkflowNotFoundException.class, () -> workflowService.deleteWorkflow("wf-1", "user-B"));
        verifyNoInteractions(scheduledOccurrenceRepository);
        verify(workflowRepository, never()).deleteByIdAndUserId(anyString(), anyString());
    }

    // ==========================================
    // Regenerate Webhook Tests
    // ==========================================

    @Test
    void regenerateWebhook_NonWebhookWorkflow_ThrowsValidationException() {
        Workflow manualWf = new Workflow("wf-manual", "user-1", "Manual WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.MANUAL, new WorkflowTriggerConfig(), Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-manual", "user-1")).thenReturn(Optional.of(manualWf));

        WorkflowValidationException ex = assertThrows(WorkflowValidationException.class,
                () -> workflowService.regenerateWebhook("wf-manual", "user-1"));
        assertTrue(ex.getMessage().contains("Cannot regenerate webhook for workflow with trigger type"));
    }

    @Test
    void regenerateWebhook_WebhookWorkflow_Generates64CharCapabilityPathAndSecret() {
        WorkflowTriggerConfig config = new WorkflowTriggerConfig(null, null, null, null, false);
        Workflow webhookWf = new Workflow("wf-wh", "user-1", "WH WF", null, WorkflowStatus.ACTIVE,
                List.of(), List.of(), WorkflowTriggerType.WEBHOOK, config, Instant.now(), Instant.now());

        when(workflowRepository.findByIdAndUserId("wf-wh", "user-1")).thenReturn(Optional.of(webhookWf));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WebhookRegenerateResponse response = workflowService.regenerateWebhook("wf-wh", "user-1");

        assertNotNull(response);
        assertEquals(64, response.webhookPath().length(), "Generated webhook capability path must be 64 characters");
        assertTrue(response.secret().startsWith("whsec_"));
        assertEquals("/api/webhooks/" + response.webhookPath(), response.webhookUrl());
    }
}
