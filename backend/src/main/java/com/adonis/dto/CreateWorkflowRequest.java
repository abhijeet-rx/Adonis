package com.adonis.dto;

import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateWorkflowRequest(
        @NotBlank(message = "Workflow name is required")
        @Size(max = 100, message = "Workflow name must not exceed 100 characters")
        String name,

        @Size(max = 500, message = "Workflow description must not exceed 500 characters")
        String description,

        WorkflowStatus status,

        List<WorkflowNode> nodes,

        List<WorkflowEdge> edges,

        WorkflowTriggerType triggerType,

        WorkflowTriggerConfigRequest triggerConfig
) {
    public CreateWorkflowRequest(String name, String description, WorkflowStatus status,
                                 List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        this(name, description, status, nodes, edges, WorkflowTriggerType.MANUAL, null);
    }
}
