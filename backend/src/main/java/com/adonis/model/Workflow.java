package com.adonis.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "workflows")
@CompoundIndexes({
        @CompoundIndex(
                name = "wf_webhook_path_idx",
                def = "{'triggerConfig.webhookPath': 1}",
                unique = true,
                partialFilter = "{'triggerConfig.webhookPath': {'$exists': true, '$type': 'string'}}"
        )
})
public class Workflow {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String name;

    private String description;

    private WorkflowStatus status;

    private List<WorkflowNode> nodes = new ArrayList<>();

    private List<WorkflowEdge> edges = new ArrayList<>();

    @Indexed
    private WorkflowTriggerType triggerType = WorkflowTriggerType.MANUAL;

    private WorkflowTriggerConfig triggerConfig = new WorkflowTriggerConfig();

    private Instant createdAt;

    private Instant updatedAt;

    public Workflow() {
    }

    public Workflow(String id, String userId, String name, String description, WorkflowStatus status,
                    List<WorkflowNode> nodes, List<WorkflowEdge> edges, Instant createdAt, Instant updatedAt) {
        this(id, userId, name, description, status, nodes, edges, WorkflowTriggerType.MANUAL, new WorkflowTriggerConfig(), createdAt, updatedAt);
    }

    public Workflow(String id, String userId, String name, String description, WorkflowStatus status,
                    List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                    WorkflowTriggerType triggerType, WorkflowTriggerConfig triggerConfig,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.nodes = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
        this.edges = edges != null ? new ArrayList<>(edges) : new ArrayList<>();
        this.triggerType = triggerType != null ? triggerType : WorkflowTriggerType.MANUAL;
        this.triggerConfig = triggerConfig != null ? triggerConfig : new WorkflowTriggerConfig();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Workflow create(String userId, String name, String description, WorkflowStatus status,
                                  List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return create(userId, name, description, status, nodes, edges, WorkflowTriggerType.MANUAL, new WorkflowTriggerConfig());
    }

    public static Workflow create(String userId, String name, String description, WorkflowStatus status,
                                  List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                  WorkflowTriggerType triggerType, WorkflowTriggerConfig triggerConfig) {
        Instant now = Instant.now();
        return new Workflow(
                null,
                userId,
                name != null ? name.trim() : null,
                description != null ? description.trim() : null,
                status != null ? status : WorkflowStatus.DRAFT,
                nodes != null ? new ArrayList<>(nodes) : new ArrayList<>(),
                edges != null ? new ArrayList<>(edges) : new ArrayList<>(),
                triggerType != null ? triggerType : WorkflowTriggerType.MANUAL,
                triggerConfig != null ? triggerConfig : new WorkflowTriggerConfig(),
                now,
                now
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description != null ? description.trim() : null;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
    }

    public List<WorkflowNode> getNodes() {
        return nodes != null ? nodes : new ArrayList<>();
    }

    public void setNodes(List<WorkflowNode> nodes) {
        this.nodes = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
    }

    public List<WorkflowEdge> getEdges() {
        return edges != null ? edges : new ArrayList<>();
    }

    public void setEdges(List<WorkflowEdge> edges) {
        this.edges = edges != null ? new ArrayList<>(edges) : new ArrayList<>();
    }

    public WorkflowTriggerType getTriggerType() {
        return triggerType != null ? triggerType : WorkflowTriggerType.MANUAL;
    }

    public void setTriggerType(WorkflowTriggerType triggerType) {
        this.triggerType = triggerType != null ? triggerType : WorkflowTriggerType.MANUAL;
    }

    public WorkflowTriggerConfig getTriggerConfig() {
        if (triggerConfig == null) {
            triggerConfig = new WorkflowTriggerConfig();
        }
        return triggerConfig;
    }

    public void setTriggerConfig(WorkflowTriggerConfig triggerConfig) {
        this.triggerConfig = triggerConfig != null ? triggerConfig : new WorkflowTriggerConfig();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
