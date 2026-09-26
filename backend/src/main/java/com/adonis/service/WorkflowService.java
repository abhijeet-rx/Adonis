package com.adonis.service;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WorkflowResponse;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowStatus;
import com.adonis.repository.WorkflowRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class WorkflowService {

    private final WorkflowRepository workflowRepository;

    public WorkflowService(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    public WorkflowResponse createWorkflow(String userId, CreateWorkflowRequest request) {
        WorkflowStatus status = request.status() != null ? request.status() : WorkflowStatus.DRAFT;
        Workflow workflow = Workflow.create(
                userId,
                request.name(),
                request.description(),
                status,
                request.nodes() != null ? request.nodes() : new ArrayList<>(),
                request.edges() != null ? request.edges() : new ArrayList<>()
        );
        Workflow saved = workflowRepository.save(workflow);
        return WorkflowResponse.fromWorkflow(saved);
    }

    public List<WorkflowResponse> listWorkflows(String userId) {
        return workflowRepository.findByUserId(userId).stream()
                .map(WorkflowResponse::fromWorkflow)
                .toList();
    }

    public WorkflowResponse getWorkflow(String id, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + id));
        return WorkflowResponse.fromWorkflow(workflow);
    }

    public WorkflowResponse updateWorkflow(String id, String userId, UpdateWorkflowRequest request) {
        Workflow workflow = workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + id));

        workflow.setName(request.name().trim());
        workflow.setDescription(request.description() != null ? request.description().trim() : null);
        if (request.status() != null) {
            workflow.setStatus(request.status());
        }
        if (request.nodes() != null) {
            workflow.setNodes(request.nodes());
        }
        if (request.edges() != null) {
            workflow.setEdges(request.edges());
        }
        workflow.setUpdatedAt(Instant.now());

        Workflow saved = workflowRepository.save(workflow);
        return WorkflowResponse.fromWorkflow(saved);
    }

    public void deleteWorkflow(String id, String userId) {
        long deletedCount = workflowRepository.deleteByIdAndUserId(id, userId);
        if (deletedCount == 0) {
            throw new WorkflowNotFoundException("Workflow not found with id: " + id);
        }
    }
}
