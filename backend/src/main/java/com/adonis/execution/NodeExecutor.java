package com.adonis.execution;

import com.adonis.model.WorkflowNode;

import java.util.Map;

public interface NodeExecutor {

    boolean supports(String nodeType);

    NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context);
}
