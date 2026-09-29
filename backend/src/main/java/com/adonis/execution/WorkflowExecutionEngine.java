package com.adonis.execution;

import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class WorkflowExecutionEngine {

    private final List<NodeExecutor> executors;
    private final RetryPolicy retryPolicy;

    public WorkflowExecutionEngine(List<NodeExecutor> executors) {
        this(executors, new RetryPolicy(new FailureClassifier()));
    }

    @Autowired
    public WorkflowExecutionEngine(List<NodeExecutor> executors, RetryPolicy retryPolicy) {
        this.executors = executors != null ? executors : List.of();
        this.retryPolicy = retryPolicy != null ? retryPolicy : new RetryPolicy(new FailureClassifier());
    }

    /**
     * Executes the workflow nodes sequentially in the provided topological order.
     * Implements fail-fast behavior: stops at first failed node without running downstream steps.
     *
     * @param workflow the original workflow
     * @param executionOrder topologically sorted node sequence
     * @param userId authenticated user ID
     * @return structured WorkflowExecutionResult
     */
    public WorkflowExecutionResult execute(Workflow workflow, List<WorkflowNode> executionOrder, String userId) {
        return execute(workflow, executionOrder, userId, UUID.randomUUID().toString());
    }

    public WorkflowExecutionResult execute(Workflow workflow, List<WorkflowNode> executionOrder, String userId, String executionId) {
        Instant startedAt = Instant.now();
        String effectiveExecutionId = executionId != null ? executionId : UUID.randomUUID().toString();
        ExecutionContext context = new ExecutionContext(effectiveExecutionId, workflow.getId(), userId, startedAt);

        // Map node ID to list of upstream node IDs targeting it
        Map<String, List<String>> upstreamMap = new HashMap<>();
        for (WorkflowNode node : workflow.getNodes()) {
            upstreamMap.put(node.getId(), new ArrayList<>());
        }
        if (workflow.getEdges() != null) {
            for (WorkflowEdge edge : workflow.getEdges()) {
                if (upstreamMap.containsKey(edge.getTarget())) {
                    upstreamMap.get(edge.getTarget()).add(edge.getSource());
                }
            }
        }

        List<NodeExecutionResult> executedNodes = new ArrayList<>();

        for (WorkflowNode node : executionOrder) {
            // Determine input from upstream nodes
            List<String> upstreamSourceIds = upstreamMap.getOrDefault(node.getId(), List.of());
            Map<String, Object> input = resolveNodeInput(upstreamSourceIds, context);

            // Find matching executor
            NodeExecutor executor = executors.stream()
                    .filter(e -> e.supports(node.getType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No executor found for node type: " + node.getType()));

            // Execute node with retry policy
            NodeExecutionResult nodeResult = retryPolicy.executeWithRetry(node, input, context, executor);
            context.recordNodeResult(node.getId(), nodeResult);
            executedNodes.add(nodeResult);

            // Fail-fast on node failure
            if (nodeResult.status() == ExecutionStatus.FAILED) {
                Instant completedAt = Instant.now();
                String errorMessage = "Node '" + node.getId() + "' [" + node.getType() + "] failed: " +
                        (nodeResult.error() != null ? nodeResult.error() : "Unknown error");
                return WorkflowExecutionResult.failure(
                        effectiveExecutionId,
                        workflow.getId(),
                        startedAt,
                        completedAt,
                        executedNodes,
                        errorMessage
                );
            }
        }

        Instant completedAt = Instant.now();
        return WorkflowExecutionResult.success(
                effectiveExecutionId,
                workflow.getId(),
                startedAt,
                completedAt,
                executedNodes
        );
    }

    private Map<String, Object> resolveNodeInput(List<String> upstreamSourceIds, ExecutionContext context) {
        if (upstreamSourceIds.isEmpty()) {
            return Map.of();
        }

        if (upstreamSourceIds.size() == 1) {
            NodeExecutionResult upstreamResult = context.getNodeResult(upstreamSourceIds.get(0));
            if (upstreamResult != null && upstreamResult.output() != null) {
                return upstreamResult.output();
            }
            return Map.of();
        }

        // Multiple upstream nodes: combine deterministically keyed by source node ID
        Map<String, Object> combined = new LinkedHashMap<>();
        for (String sourceId : upstreamSourceIds) {
            NodeExecutionResult upstreamResult = context.getNodeResult(sourceId);
            if (upstreamResult != null && upstreamResult.output() != null) {
                combined.put(sourceId, upstreamResult.output());
            } else {
                combined.put(sourceId, Map.of());
            }
        }
        return combined;
    }
}
