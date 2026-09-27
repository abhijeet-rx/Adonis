package com.adonis.execution;

import com.adonis.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TriggerNodeExecutor implements NodeExecutor {

    @Override
    public boolean supports(String nodeType) {
        return nodeType != null && "trigger".equalsIgnoreCase(nodeType.trim());
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
        Instant startedAt = Instant.now();
        Map<String, Object> nodeData = node.getData() != null ? node.getData() : Map.of();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("trigger", nodeData.getOrDefault("triggerType", "manual"));
        output.put("timestamp", startedAt.toString());
        output.put("label", nodeData.getOrDefault("label", "Manual Trigger"));
        output.put("data", input != null && !input.isEmpty() ? input : Map.of());

        Instant completedAt = Instant.now();
        return NodeExecutionResult.success(node.getId(), node.getType(), startedAt, completedAt, output);
    }
}
