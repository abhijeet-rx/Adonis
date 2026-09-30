package com.adonis.execution;

import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowTriggerType;
import com.adonis.repository.WorkflowExecutionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class WorkflowExecutionEngine {

    private final List<NodeExecutor> executors;
    private final RetryPolicy retryPolicy;
    private final WorkflowExecutionRepository executionRepository;

    public WorkflowExecutionEngine(List<NodeExecutor> executors) {
        this(executors, new RetryPolicy(new FailureClassifier()), null);
    }

    public WorkflowExecutionEngine(List<NodeExecutor> executors, RetryPolicy retryPolicy) {
        this(executors, retryPolicy, null);
    }

    @Autowired
    public WorkflowExecutionEngine(
            List<NodeExecutor> executors,
            @Autowired(required = false) RetryPolicy retryPolicy,
            @Autowired(required = false) WorkflowExecutionRepository executionRepository) {
        this.executors = executors != null ? executors : List.of();
        this.retryPolicy = retryPolicy != null ? retryPolicy : new RetryPolicy(new FailureClassifier());
        this.executionRepository = executionRepository;
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
        return execute(workflow, executionOrder, userId, UUID.randomUUID().toString(), TriggerContext.manual(userId));
    }

    public WorkflowExecutionResult execute(Workflow workflow, List<WorkflowNode> executionOrder, String userId, String executionId) {
        TriggerContext triggerContext = resolveTriggerContext(workflow, userId, executionId);
        return execute(workflow, executionOrder, userId, executionId, triggerContext);
    }

    private TriggerContext resolveTriggerContext(Workflow workflow, String userId, String executionId) {
        if (executionRepository != null && executionId != null) {
            try {
                Optional<WorkflowExecution> execOpt = executionRepository.findById(executionId);
                if (execOpt.isPresent()) {
                    WorkflowExecution exec = execOpt.get();
                    WorkflowTriggerType triggerType = WorkflowTriggerType.from(exec.getTriggerType());
                    Map<String, Object> payload = exec.getTriggerPayload() != null ? exec.getTriggerPayload() : Map.of();
                    return new TriggerContext(triggerType, payload, Map.of("userId", userId != null ? userId : ""));
                }
            } catch (Exception ignored) {
                // Fall back gracefully if repository lookup fails
            }
        }
        if (workflow != null && workflow.getTriggerType() != null && workflow.getTriggerType() != WorkflowTriggerType.MANUAL) {
            return new TriggerContext(workflow.getTriggerType(), Map.of(), Map.of("userId", userId != null ? userId : ""));
        }
        return TriggerContext.manual(userId);
    }

    public WorkflowExecutionResult execute(
            Workflow workflow,
            List<WorkflowNode> executionOrder,
            String userId,
            String executionId,
            TriggerContext triggerContext) {
        Instant startedAt = Instant.now();
        String effectiveExecutionId = executionId != null ? executionId : UUID.randomUUID().toString();
        ExecutionContext context = new ExecutionContext(effectiveExecutionId, workflow.getId(), userId, startedAt, triggerContext);

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
