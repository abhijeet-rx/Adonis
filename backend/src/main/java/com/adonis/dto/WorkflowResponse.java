package com.adonis.dto;

import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowStatus;

import java.time.Instant;
import java.util.List;

public record WorkflowResponse(
        String id,
        String userId,
        String name,
        String description,
        WorkflowStatus status,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkflowResponse fromWorkflow(Workflow workflow) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getUserId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getStatus(),
                workflow.getNodes(),
                workflow.getEdges(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }
}
