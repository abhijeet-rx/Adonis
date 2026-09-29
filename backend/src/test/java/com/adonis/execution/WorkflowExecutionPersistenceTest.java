package com.adonis.execution;

import com.adonis.dto.ExecutionResponse;
import com.adonis.dto.ExecutionSummaryResponse;
import com.adonis.dto.PageResponse;
import com.adonis.exception.ExecutionNotFoundException;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.NodeExecution;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionPersistenceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    private WorkflowExecutionValidator validator;
    private WorkflowExecutionEngine engine;
    private WorkflowExecutionService executionService;

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
    }

    @Test
    void executeWorkflow_Success_PersistsExecutionRecordWithStatusAndTimestamps() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-persist-success");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of("label", "Start Manual", "triggerType", "manual")),
                new WorkflowNode("g1", "generic", Map.of("label", "Generic Step"))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "g1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-persist-success", "user-1"))
                .thenReturn(Optional.of(workflow));

        // When saved initially (RUNNING), assign an ID
        List<ExecutionStatus> savedStatuses = new ArrayList<>();
        when(executionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(invocation -> {
                    WorkflowExecution exec = invocation.getArgument(0);
                    savedStatuses.add(exec.getStatus());
                    if (exec.getId() == null) {
                        exec.setId("exec-mongo-123");
                    }
                    return exec;
                });

        WorkflowExecutionResult result = executionService.executeWorkflow("wf-persist-success", "user-1");

        // Verify result
        assertNotNull(result);
        assertEquals("exec-mongo-123", result.executionId());
        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
        assertTrue(result.durationMs() >= 0);

        // Verify state progression: RUNNING -> SUCCESS
        assertEquals(2, savedStatuses.size());
        assertEquals(ExecutionStatus.RUNNING, savedStatuses.get(0));
        assertEquals(ExecutionStatus.SUCCESS, savedStatuses.get(1));

        // Capture calls to executionRepository.save
        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository, times(2)).save(captor.capture());

        WorkflowExecution finalSave = captor.getValue();
        assertEquals(ExecutionStatus.SUCCESS, finalSave.getStatus());
        assertEquals("exec-mongo-123", finalSave.getId());
        assertNotNull(finalSave.getCompletedAt());
        assertNotNull(finalSave.getDurationMs());
        assertNull(finalSave.getError());

        // Verify node executions persisted
        assertEquals(2, finalSave.getNodeExecutions().size());
        NodeExecution n0 = finalSave.getNodeExecutions().get(0);
        assertEquals("t1", n0.getNodeId());
        assertEquals("trigger", n0.getNodeType());
        assertEquals(ExecutionStatus.SUCCESS, n0.getStatus());

        NodeExecution n1 = finalSave.getNodeExecutions().get(1);
        assertEquals("g1", n1.getNodeId());
        assertEquals("generic", n1.getNodeType());
        assertEquals(ExecutionStatus.SUCCESS, n1.getStatus());
    }

    @Test
    void executeWorkflow_FailFast_PersistsFailedExecutionAndDownstreamAsSkipped() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-persist-failure");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of("label", "Start Manual")),
                new WorkflowNode("h1", "httpRequest", Map.of(
                        // Unreachable port
                        "url", "http://127.0.0.1:59998/unreachable",
                        "method", "GET"
                )),
                new WorkflowNode("g1", "generic", Map.of("label", "Downstream Generic Step"))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "h1"),
                new WorkflowEdge("e2", "h1", "g1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-persist-failure", "user-1"))
                .thenReturn(Optional.of(workflow));

        when(executionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(invocation -> {
                    WorkflowExecution exec = invocation.getArgument(0);
                    if (exec.getId() == null) {
                        exec.setId("exec-mongo-fail");
                    }
                    return exec;
                });

        WorkflowExecutionResult result = executionService.executeWorkflow("wf-persist-failure", "user-1");

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());

        // Verify persistent entity in repository
        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository, times(2)).save(captor.capture());

        WorkflowExecution finalSave = captor.getAllValues().get(1);
        assertEquals(ExecutionStatus.FAILED, finalSave.getStatus());
        assertNotNull(finalSave.getCompletedAt());
        assertNotNull(finalSave.getDurationMs());
        assertNotNull(finalSave.getError());

        // Verify node executions: t1 SUCCESS, h1 FAILED, g1 SKIPPED
        List<NodeExecution> nodes = finalSave.getNodeExecutions();
        assertEquals(3, nodes.size(), "All nodes in execution order should be recorded including SKIPPED ones");

        NodeExecution node0 = nodes.get(0);
        assertEquals("t1", node0.getNodeId());
        assertEquals(ExecutionStatus.SUCCESS, node0.getStatus());

        NodeExecution node1 = nodes.get(1);
        assertEquals("h1", node1.getNodeId());
        assertEquals(ExecutionStatus.FAILED, node1.getStatus());
        assertNotNull(node1.getError());

        NodeExecution node2 = nodes.get(2);
        assertEquals("g1", node2.getNodeId());
        assertEquals(ExecutionStatus.SKIPPED, node2.getStatus());
        assertEquals(0L, node2.getDurationMs());
        assertNull(node2.getError());
    }

    @Test
    void executeWorkflow_SecretRedaction_RedactsSensitiveHeadersInPersistedNodes() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-secrets");
        workflow.setUserId("user-1");
        workflow.setNodes(List.of(
                new WorkflowNode("t1", "trigger", Map.of("label", "Start")),
                new WorkflowNode("h1", "httpRequest", Map.of(
                        "url", "http://127.0.0.1:59999/dummy",
                        "method", "GET",
                        "headers", Map.of(
                                "Authorization", "Bearer sensitive-secret-token",
                                "X-Api-Key", "my-api-key-999"
                        )
                ))
        ));
        workflow.setEdges(List.of(
                new WorkflowEdge("e1", "t1", "h1")
        ));

        when(workflowRepository.findByIdAndUserId("wf-secrets", "user-1"))
                .thenReturn(Optional.of(workflow));

        when(executionRepository.save(any(WorkflowExecution.class)))
                .thenAnswer(invocation -> {
                    WorkflowExecution exec = invocation.getArgument(0);
                    if (exec.getId() == null) {
                        exec.setId("exec-secret-test");
                    }
                    return exec;
                });

        executionService.executeWorkflow("wf-secrets", "user-1");

        ArgumentCaptor<WorkflowExecution> captor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository, times(2)).save(captor.capture());

        WorkflowExecution finalSave = captor.getAllValues().get(1);
        assertEquals(2, finalSave.getNodeExecutions().size());
        NodeExecution httpNode = finalSave.getNodeExecutions().get(1);

        // Verify inputs are redacted
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) httpNode.getInput().get("headers");
        assertNotNull(headers);
        assertEquals("[REDACTED]", headers.get("Authorization"));
        assertEquals("[REDACTED]", headers.get("X-Api-Key"));
    }

    @Test
    void getExecution_OwnershipMismatch_ThrowsExecutionNotFoundException() {
        when(executionRepository.findByIdAndUserId("exec-unowned", "user-attacker"))
                .thenReturn(Optional.empty());

        assertThrows(
                ExecutionNotFoundException.class,
                () -> executionService.getExecution("exec-unowned", "user-attacker")
        );
    }

    @Test
    void getExecution_Owned_ReturnsFullExecutionResponse() {
        WorkflowExecution exec = new WorkflowExecution();
        exec.setId("exec-100");
        exec.setWorkflowId("wf-1");
        exec.setUserId("user-1");
        exec.setStatus(ExecutionStatus.SUCCESS);
        exec.setTriggerType("manual");
        exec.setStartedAt(Instant.now());
        exec.setCompletedAt(Instant.now());
        exec.setDurationMs(250L);
        exec.setNodeExecutions(List.of(
                new NodeExecution("t1", "trigger", ExecutionStatus.SUCCESS, Instant.now(), Instant.now(), 10L, Map.of(), Map.of(), null)
        ));

        when(executionRepository.findByIdAndUserId("exec-100", "user-1"))
                .thenReturn(Optional.of(exec));

        ExecutionResponse response = executionService.getExecution("exec-100", "user-1");

        assertNotNull(response);
        assertEquals("exec-100", response.id());
        assertEquals("exec-100", response.executionId());
        assertEquals("wf-1", response.workflowId());
        assertEquals(ExecutionStatus.SUCCESS, response.status());
        assertEquals(1, response.nodeExecutions().size());
        assertEquals("t1", response.nodeExecutions().get(0).nodeId());
    }

    @Test
    void getWorkflowExecutions_WorkflowNotOwned_ThrowsWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-unowned", "user-attacker"))
                .thenReturn(Optional.empty());

        assertThrows(
                WorkflowNotFoundException.class,
                () -> executionService.getWorkflowExecutions("wf-unowned", "user-attacker", PageRequest.of(0, 10))
        );
        verify(executionRepository, never()).findByWorkflowIdAndUserId(any(), any(), any());
    }

    @Test
    void getWorkflowExecutions_Owned_ReturnsSummaryPageWithoutNodePayloads() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-1");
        workflow.setUserId("user-1");

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1"))
                .thenReturn(Optional.of(workflow));

        WorkflowExecution exec1 = new WorkflowExecution("e1", "wf-1", "user-1", ExecutionStatus.SUCCESS, "manual", Instant.now(), Instant.now(), 500L, List.of(), null);
        Page<WorkflowExecution> page = new PageImpl<>(List.of(exec1), PageRequest.of(0, 10), 1);

        when(executionRepository.findByWorkflowIdAndUserId(eq("wf-1"), eq("user-1"), any(Pageable.class)))
                .thenReturn(page);

        PageResponse<ExecutionSummaryResponse> response = executionService.getWorkflowExecutions("wf-1", "user-1", PageRequest.of(0, 10));

        assertEquals(1, response.totalElements());
        assertEquals(1, response.content().size());
        assertEquals("e1", response.content().get(0).id());
        assertEquals(ExecutionStatus.SUCCESS, response.content().get(0).status());
    }
}
