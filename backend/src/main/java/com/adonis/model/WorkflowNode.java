package com.adonis.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class WorkflowNode {

    private String id;
    private String type;
    private Map<String, Object> data = new HashMap<>();
    private WorkflowNodePosition position;

    public WorkflowNode() {
    }

    public WorkflowNode(String id, String type, Map<String, Object> data) {
        this(id, type, data, new WorkflowNodePosition(0.0, 0.0));
    }

    public WorkflowNode(String id, String type, Map<String, Object> data, WorkflowNodePosition position) {
        this.id = id;
        this.type = type;
        this.data = data != null ? data : new HashMap<>();
        this.position = position != null ? position : new WorkflowNodePosition(0.0, 0.0);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getData() {
        return data != null ? data : new HashMap<>();
    }

    public void setData(Map<String, Object> data) {
        this.data = data != null ? data : new HashMap<>();
    }

    public WorkflowNodePosition getPosition() {
        return position;
    }

    public void setPosition(WorkflowNodePosition position) {
        this.position = position != null ? position : new WorkflowNodePosition(0.0, 0.0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowNode that)) return false;
        return Objects.equals(id, that.id) &&
                Objects.equals(type, that.type) &&
                Objects.equals(data, that.data) &&
                Objects.equals(position, that.position);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, data, position);
    }
}
