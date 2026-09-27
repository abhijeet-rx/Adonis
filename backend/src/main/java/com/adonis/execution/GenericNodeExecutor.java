package com.adonis.execution;

import com.adonis.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GenericNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(String nodeType) {
        if (nodeType == null) return false;
        String normalized = nodeType.trim().toLowerCase();
        return "generic".equals(normalized) || "custom".equals(normalized);
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
        Instant startedAt = Instant.now();
        Map<String, Object> nodeData = node.getData() != null ? node.getData() : Map.of();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("nodeId", node.getId());
        output.put("label", nodeData.getOrDefault("label", "Generic Step"));
        output.put("description", nodeData.getOrDefault("description", ""));

        // Pass-through received input
        if (input != null && !input.isEmpty()) {
            output.put("input", input);
            output.putAll(input);
        }

        Instant completedAt = Instant.now();
        return NodeExecutionResult.success(node.getId(), node.getType(), startedAt, completedAt, output);
    }
}
