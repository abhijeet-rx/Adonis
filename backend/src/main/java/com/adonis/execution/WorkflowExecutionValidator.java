package com.adonis.execution;

import com.adonis.exception.WorkflowValidationException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class WorkflowExecutionValidator {

    private final List<NodeExecutor> executors;

    public WorkflowExecutionValidator(List<NodeExecutor> executors) {
        this.executors = executors != null ? executors : List.of();
    }

    /**
     * Validates the workflow definition against all Phase 4 constraints
     * and returns the deterministic topological execution order of nodes.
     *
     * @param workflow the workflow to validate
     * @return ordered list of nodes to execute
     * @throws WorkflowValidationException if any validation rule is violated
     */
    public List<WorkflowNode> validateAndOrder(Workflow workflow) {
        if (workflow == null) {
            throw new WorkflowValidationException("Workflow cannot be null");
        }

        List<WorkflowNode> nodes = workflow.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            throw new WorkflowValidationException("Workflow contains no nodes and cannot be executed");
        }

        // 1. Check unique node IDs
        Set<String> nodeIds = new HashSet<>();
        Map<String, WorkflowNode> nodeMap = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (node.getId() == null || node.getId().trim().isEmpty()) {
                throw new WorkflowValidationException("Workflow contains a node with a missing or blank ID");
            }
            if (!nodeIds.add(node.getId())) {
                throw new WorkflowValidationException("Duplicate node ID detected in workflow: '" + node.getId() + "'");
            }
            nodeMap.put(node.getId(), node);
        }

        // 2. Check supported node types and validate node configuration
        for (WorkflowNode node : nodes) {
            NodeExecutor executor = executors.stream()
                    .filter(e -> e.supports(node.getType()))
                    .findFirst()
                    .orElseThrow(() -> new WorkflowValidationException(
                            "Unsupported node type '" + node.getType() + "' on node '" + node.getId() + "'"
                    ));
            executor.validate(node);
        }

        // 3. Check exactly one trigger node
        List<WorkflowNode> triggerNodes = nodes.stream()
                .filter(n -> n.getType() != null && "trigger".equalsIgnoreCase(n.getType().trim()))
                .toList();

        if (triggerNodes.isEmpty()) {
            throw new WorkflowValidationException("Workflow must have exactly one trigger node (found 0)");
        }
        if (triggerNodes.size() > 1) {
            throw new WorkflowValidationException("Workflow must have exactly one trigger node (found " + triggerNodes.size() + ")");
        }
        WorkflowNode triggerNode = triggerNodes.get(0);

        // 4. Check valid edge references
        List<WorkflowEdge> edges = workflow.getEdges() != null ? workflow.getEdges() : List.of();
        for (WorkflowEdge edge : edges) {
            if (edge.getSource() == null || !nodeMap.containsKey(edge.getSource())) {
                throw new WorkflowValidationException(
                        "Edge '" + edge.getId() + "' references non-existent source node: '" + edge.getSource() + "'"
                );
            }
            if (edge.getTarget() == null || !nodeMap.containsKey(edge.getTarget())) {
                throw new WorkflowValidationException(
                        "Edge '" + edge.getId() + "' references non-existent target node: '" + edge.getTarget() + "'"
                );
            }
        }

        // 5. Build graph adjacency list and in-degrees for Kahn's algorithm
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> adjList = new LinkedHashMap<>();

        for (WorkflowNode node : nodes) {
            inDegree.put(node.getId(), 0);
            adjList.put(node.getId(), new ArrayList<>());
        }

        for (WorkflowEdge edge : edges) {
            adjList.get(edge.getSource()).add(edge.getTarget());
            inDegree.put(edge.getTarget(), inDegree.get(edge.getTarget()) + 1);
        }

        // Trigger node cannot have incoming edges
        if (inDegree.get(triggerNode.getId()) > 0) {
            throw new WorkflowValidationException("Trigger node '" + triggerNode.getId() + "' cannot have incoming edges");
        }

        // 6. Kahn's Algorithm for Topological Sort and Cycle Detection
        Queue<String> queue = new ArrayDeque<>();
        // Trigger executes first
        queue.add(triggerNode.getId());

        // Any other root nodes with in-degree 0
        for (WorkflowNode node : nodes) {
            if (!node.getId().equals(triggerNode.getId()) && inDegree.get(node.getId()) == 0) {
                queue.add(node.getId());
            }
        }

        List<WorkflowNode> executionOrder = new ArrayList<>();
        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            executionOrder.add(nodeMap.get(currentId));

            for (String neighborId : adjList.get(currentId)) {
                int updatedInDegree = inDegree.get(neighborId) - 1;
                inDegree.put(neighborId, updatedInDegree);
                if (updatedInDegree == 0) {
                    queue.add(neighborId);
                }
            }
        }

        // If not all nodes were processed, there is a cycle
        if (executionOrder.size() < nodes.size()) {
            throw new WorkflowValidationException(
                    "Workflow execution graph contains a cycle (graph must be a directed acyclic graph)"
            );
        }

        return executionOrder;
    }
}
