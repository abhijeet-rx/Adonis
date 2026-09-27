package com.adonis.execution;

import com.adonis.exception.WorkflowValidationException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowExecutionValidatorTest {

    private WorkflowExecutionValidator validator;

    @BeforeEach
    void setUp() {
        List<NodeExecutor> executors = List.of(
                new TriggerNodeExecutor(),
                new HttpRequestNodeExecutor(),
                new GenericNodeExecutor()
        );
        validator = new WorkflowExecutionValidator(executors);
    }

    @Test
    void validate_NullWorkflow_ThrowsException() {
        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(null)
        );
        assertTrue(ex.getMessage().contains("Workflow cannot be null"));
    }

    @Test
    void validate_EmptyNodes_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of());

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("no nodes"));
    }

    @Test
    void validate_DuplicateNodeIds_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("node-1", "trigger", Map.of()),
                new WorkflowNode("node-1", "generic", Map.of())
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("Duplicate node ID"));
    }

    @Test
    void validate_BlankNodeId_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("  ", "trigger", Map.of())
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("missing or blank ID"));
    }

    @Test
    void validate_UnsupportedNodeType_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("node-1", "trigger", Map.of()),
                new WorkflowNode("node-2", "aiAgent", Map.of())
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("Unsupported node type 'aiAgent'"));
    }

    @Test
    void validate_ZeroTriggers_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("node-1", "generic", Map.of())
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("must have exactly one trigger node (found 0)"));
    }

    @Test
    void validate_MultipleTriggers_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("trigger-1", "trigger", Map.of()),
                new WorkflowNode("trigger-2", "trigger", Map.of())
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("must have exactly one trigger node (found 2)"));
    }

    @Test
    void validate_TriggerWithIncomingEdge_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("trigger-1", "trigger", Map.of()),
                new WorkflowNode("generic-1", "generic", Map.of())
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("edge-1", "generic-1", "trigger-1")
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("Trigger node 'trigger-1' cannot have incoming edges"));
    }

    @Test
    void validate_EdgeReferencesNonExistentSource_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("trigger-1", "trigger", Map.of()),
                new WorkflowNode("node-2", "generic", Map.of())
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("edge-1", "non-existent", "node-2")
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("references non-existent source node: 'non-existent'"));
    }

    @Test
    void validate_EdgeReferencesNonExistentTarget_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("trigger-1", "trigger", Map.of()),
                new WorkflowNode("node-2", "generic", Map.of())
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("edge-1", "trigger-1", "missing-target")
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("references non-existent target node: 'missing-target'"));
    }

    @Test
    void validate_CycleDetection_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("trigger-1", "trigger", Map.of()),
                new WorkflowNode("node-A", "generic", Map.of()),
                new WorkflowNode("node-B", "generic", Map.of())
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "trigger-1", "node-A"),
                new WorkflowEdge("e2", "node-A", "node-B"),
                new WorkflowEdge("e3", "node-B", "node-A") // Cycle between A and B
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void validate_SelfReferencingCycle_ThrowsException() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("trigger-1", "trigger", Map.of()),
                new WorkflowNode("node-A", "generic", Map.of())
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "trigger-1", "node-A"),
                new WorkflowEdge("e2", "node-A", "node-A")
        ));

        WorkflowValidationException ex = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validateAndOrder(workflow)
        );
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void validate_ValidLinearWorkflow_ReturnsTopologicalOrder() {
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("step-generic", "generic", Map.of()),
                new WorkflowNode("step-trigger", "trigger", Map.of()),
                new WorkflowNode("step-http", "httpRequest", Map.of("url", "https://example.com"))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "step-trigger", "step-http"),
                new WorkflowEdge("e2", "step-http", "step-generic")
        ));

        List<WorkflowNode> order = validator.validateAndOrder(workflow);

        assertEquals(3, order.size());
        assertEquals("step-trigger", order.get(0).getId());
        assertEquals("step-http", order.get(1).getId());
        assertEquals("step-generic", order.get(2).getId());
    }

    @Test
    void validate_ValidBranchingWorkflow_ReturnsValidTopologicalOrder() {
        // Trigger -> BranchA, Trigger -> BranchB, BranchA -> Join, BranchB -> Join
        Workflow workflow = new Workflow();
        workflow.setNodes(List.of(
                new WorkflowNode("join", "generic", Map.of()),
                new WorkflowNode("branch-b", "generic", Map.of()),
                new WorkflowNode("trigger", "trigger", Map.of()),
                new WorkflowNode("branch-a", "generic", Map.of())
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "trigger", "branch-a"),
                new WorkflowEdge("e2", "trigger", "branch-b"),
                new WorkflowEdge("e3", "branch-a", "join"),
                new WorkflowEdge("e4", "branch-b", "join")
        ));

        List<WorkflowNode> order = validator.validateAndOrder(workflow);

        assertEquals(4, order.size());
        assertEquals("trigger", order.get(0).getId());
        assertEquals("join", order.get(3).getId());
        // branch-a and branch-b must be between trigger and join
        List<String> middleIds = List.of(order.get(1).getId(), order.get(2).getId());
        assertTrue(middleIds.contains("branch-a"));
        assertTrue(middleIds.contains("branch-b"));
    }
}
