package com.adonis.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "workflows")
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

    private Instant createdAt;

    private Instant updatedAt;

    public Workflow() {
    }

    public Workflow(String id, String userId, String name, String description, WorkflowStatus status,
                    List<WorkflowNode> nodes, List<WorkflowEdge> edges, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.nodes = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
        this.edges = edges != null ? new ArrayList<>(edges) : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Workflow create(String userId, String name, String description, WorkflowStatus status,
                                  List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Instant now = Instant.now();
        return new Workflow(
                null,
                userId,
                name != null ? name.trim() : null,
                description != null ? description.trim() : null,
                status != null ? status : WorkflowStatus.DRAFT,
                nodes != null ? new ArrayList<>(nodes) : new ArrayList<>(),
                edges != null ? new ArrayList<>(edges) : new ArrayList<>(),
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
