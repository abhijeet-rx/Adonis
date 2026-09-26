package com.adonis.service;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WorkflowResponse;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowStatus;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(workflowRepository);
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
        // When user-B tries to get user-A's workflow, repository returns empty
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
    void updateWorkflow_WhenBelongsToAnotherUser_ShouldThrowWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-1", "user-B")).thenReturn(Optional.empty());

        UpdateWorkflowRequest request = new UpdateWorkflowRequest("New Name", "New Desc", WorkflowStatus.ACTIVE, List.of(), List.of());

        assertThrows(WorkflowNotFoundException.class, () -> workflowService.updateWorkflow("wf-1", "user-B", request));
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void deleteWorkflow_WhenOwnedByUser_ShouldDelete() {
        Workflow existing = new Workflow("wf-1", "user-100", "WF1", "Desc", WorkflowStatus.DRAFT, List.of(), List.of(), Instant.now(), Instant.now());
        when(workflowRepository.findByIdAndUserId("wf-1", "user-100")).thenReturn(Optional.of(existing));

        workflowService.deleteWorkflow("wf-1", "user-100");

        verify(workflowRepository).delete(existing);
    }

    @Test
    void deleteWorkflow_WhenBelongsToAnotherUser_ShouldThrowWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-1", "user-B")).thenReturn(Optional.empty());

        assertThrows(WorkflowNotFoundException.class, () -> workflowService.deleteWorkflow("wf-1", "user-B"));
        verify(workflowRepository, never()).delete(any());
    }
}
