package com.adonis.dto;

import com.adonis.model.*;

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
        WorkflowTriggerType triggerType,
        WorkflowTriggerConfigResponse triggerConfig,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowResponse(String id, String userId, String name, String description,
                            WorkflowStatus status, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                            Instant createdAt, Instant updatedAt) {
        this(id, userId, name, description, status, nodes, edges,
                WorkflowTriggerType.MANUAL, WorkflowTriggerConfigResponse.fromModel(new WorkflowTriggerConfig()),
                createdAt, updatedAt);
    }

    public static WorkflowResponse fromWorkflow(Workflow workflow) {
        if (workflow == null) {
            return null;
        }
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getUserId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getStatus(),
                workflow.getNodes(),
                workflow.getEdges(),
                workflow.getTriggerType() != null ? workflow.getTriggerType() : WorkflowTriggerType.MANUAL,
                WorkflowTriggerConfigResponse.fromModel(workflow.getTriggerConfig()),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }
}
