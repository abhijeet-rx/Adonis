package com.adonis.model;

import java.util.Objects;

public class WorkflowEdge {

    private String id;
    private String source;
    private String target;
    private String sourceHandle;
    private String targetHandle;

    public WorkflowEdge() {
    }

    public WorkflowEdge(String id, String source, String target) {
        this(id, source, target, null, null);
    }

    public WorkflowEdge(String id, String source, String target, String sourceHandle, String targetHandle) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.sourceHandle = sourceHandle;
        this.targetHandle = targetHandle;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getSourceHandle() {
        return sourceHandle;
    }

    public void setSourceHandle(String sourceHandle) {
        this.sourceHandle = sourceHandle;
    }

    public String getTargetHandle() {
        return targetHandle;
    }

    public void setTargetHandle(String targetHandle) {
        this.targetHandle = targetHandle;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowEdge that)) return false;
        return Objects.equals(id, that.id) &&
                Objects.equals(source, that.source) &&
                Objects.equals(target, that.target) &&
                Objects.equals(sourceHandle, that.sourceHandle) &&
                Objects.equals(targetHandle, that.targetHandle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, source, target, sourceHandle, targetHandle);
    }
}
