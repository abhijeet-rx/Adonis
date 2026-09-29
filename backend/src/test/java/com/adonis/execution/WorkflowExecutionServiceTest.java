package com.adonis.execution;

import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    private static HttpServer localServer;
    private static String localUrl;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private com.adonis.repository.WorkflowExecutionRepository executionRepository;

    private WorkflowExecutionValidator validator;
    private WorkflowExecutionEngine engine;
    private WorkflowExecutionService executionService;

    @BeforeAll
    static void startLocalServer() throws IOException {
        localServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = localServer.getAddress().getPort();
        localUrl = "http://localhost:" + port + "/data";

        localServer.createContext("/data", exchange -> {
            byte[] responseBytes = "{\"payload\":\"backend-data\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        localServer.setExecutor(null);
        localServer.start();
    }

    @AfterAll
    static void stopLocalServer() {
        if (localServer != null) {
            localServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        List<NodeExecutor> executors = List.of(
                new TriggerNodeExecutor(),
                new HttpRequestNodeExecutor(),
                new GenericNodeExecutor()
        );
        validator = new WorkflowExecutionValidator(executors);
        engine = new WorkflowExecutionEngine(executors);
        executionService = new WorkflowExecutionService(workflowRepository, validator, engine, executionRepository);

        org.mockito.Mockito.lenient().when(executionRepository.save(org.mockito.ArgumentMatchers.any(com.adonis.model.WorkflowExecution.class)))
                .thenAnswer(inv -> {
                    com.adonis.model.WorkflowExecution exec = inv.getArgument(0);
                    if (exec.getId() == null) {
                        exec.setId("exec-test-" + java.util.UUID.randomUUID().toString().substring(0, 8));
                    }
                    return exec;
                });
    }

    @Test
    void executeWorkflow_OwnershipMismatch_ThrowsWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-100", "user-attacker"))
                .thenReturn(Optional.empty());

        assertThrows(
                WorkflowNotFoundException.class,
                () -> executionService.executeWorkflow("wf-100", "user-attacker")
        );
    }

    @Test
    void executeWorkflow_SimpleTriggerOnly_ReturnsSuccess() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-trigger-only");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of("label", "Start Manual"))
        ));

        when(workflowRepository.findByIdAndUserId("wf-trigger-only", "user-1"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult result = executionService.executeWorkflow("wf-trigger-only", "user-1");

        assertNotNull(result);
        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals("wf-trigger-only", result.workflowId());
        assertNotNull(result.executionId());
        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
        assertTrue(result.durationMs() >= 0);
        assertEquals(1, result.nodes().size());

        NodeExecutionResult nodeRes = result.nodes().get(0);
        assertEquals("t1", nodeRes.nodeId());
        assertEquals(ExecutionStatus.SUCCESS, nodeRes.status());
        assertEquals("manual", nodeRes.output().get("trigger"));
    }

    @Test
    void executeWorkflow_TriggerToGeneric_PassesData() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-linear-generic");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of("label", "Start")),
                new WorkflowNode("g1", "generic", Map.of("label", "Process"))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "g1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-linear-generic", "user-1"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult result = executionService.executeWorkflow("wf-linear-generic", "user-1");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(2, result.nodes().size());

        NodeExecutionResult genericResult = result.nodes().get(1);
        assertEquals("g1", genericResult.nodeId());
        assertEquals(ExecutionStatus.SUCCESS, genericResult.status());
        // Generic received input from t1
        assertTrue(genericResult.output().containsKey("trigger"));
        assertEquals("manual", genericResult.output().get("trigger"));
    }

    @Test
    void executeWorkflow_TriggerToHttpToGeneric_EndToEndSuccess() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-end-to-end");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of("label", "Start")),
                new WorkflowNode("h1", "httpRequest", Map.of("url", localUrl, "method", "GET")),
                new WorkflowNode("g1", "generic", Map.of("label", "Final Step"))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "h1"),
                new WorkflowEdge("e2", "h1", "g1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-end-to-end", "user-1"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult result = executionService.executeWorkflow("wf-end-to-end", "user-1");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(3, result.nodes().size());

        // Node 0: Trigger
        assertEquals("t1", result.nodes().get(0).nodeId());
        assertEquals(ExecutionStatus.SUCCESS, result.nodes().get(0).status());

        // Node 1: HTTP
        NodeExecutionResult httpResult = result.nodes().get(1);
        assertEquals("h1", httpResult.nodeId());
        assertEquals(ExecutionStatus.SUCCESS, httpResult.status());
        assertEquals(200, httpResult.output().get("statusCode"));
        assertTrue(httpResult.output().get("body").toString().contains("backend-data"));

        // Node 2: Generic
        NodeExecutionResult genResult = result.nodes().get(2);
        assertEquals("g1", genResult.nodeId());
        assertEquals(ExecutionStatus.SUCCESS, genResult.status());
        // Generic node received the HTTP output
        assertEquals(200, genResult.output().get("statusCode"));
    }

    @Test
    void executeWorkflow_BranchingWorkflow_ExecutesBothBranches() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-branch");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of()),
                new WorkflowNode("b1", "generic", Map.of("label", "Branch 1")),
                new WorkflowNode("b2", "generic", Map.of("label", "Branch 2"))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "b1"),
                new WorkflowEdge("e2", "t1", "b2")
        ));

        when(workflowRepository.findByIdAndUserId("wf-branch", "user-1"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult result = executionService.executeWorkflow("wf-branch", "user-1");

        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(3, result.nodes().size());
        assertEquals("t1", result.nodes().get(0).nodeId());
        List<String> executedBranchIds = List.of(result.nodes().get(1).nodeId(), result.nodes().get(2).nodeId());
        assertTrue(executedBranchIds.contains("b1"));
        assertTrue(executedBranchIds.contains("b2"));
    }

    @Test
    void executeWorkflow_FailFast_StopsExecutionOnFailedNode() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-fail-fast");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of()),
                new WorkflowNode("h1", "httpRequest", Map.of(
                        // Unreachable address
                        "url", "http://127.0.0.1:59997/unreachable",
                        "method", "GET"
                )),
                new WorkflowNode("g1", "generic", Map.of("label", "Downstream Should Not Run"))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "h1"),
                new WorkflowEdge("e2", "h1", "g1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-fail-fast", "user-1"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecutionResult result = executionService.executeWorkflow("wf-fail-fast", "user-1");

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Node 'h1' [httpRequest] failed"));

        // Only 2 nodes executed: t1 succeeded, h1 failed. g1 was NOT executed!
        assertEquals(2, result.nodes().size());

        NodeExecutionResult node0 = result.nodes().get(0);
        assertEquals("t1", node0.nodeId());
        assertEquals(ExecutionStatus.SUCCESS, node0.status());

        NodeExecutionResult node1 = result.nodes().get(1);
        assertEquals("h1", node1.nodeId());
        assertEquals(ExecutionStatus.FAILED, node1.status());
        assertNotNull(node1.error());

        // Verify g1 is absent from executed nodes
        boolean g1Executed = result.nodes().stream().anyMatch(n -> "g1".equals(n.nodeId()));
        assertFalse(g1Executed, "Downstream node g1 should not have been executed");
    }
}
