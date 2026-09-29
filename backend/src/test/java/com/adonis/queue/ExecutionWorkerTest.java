package com.adonis.queue;

import com.adonis.execution.*;
import com.adonis.model.NodeExecution;
import com.adonis.model.NodeExecutionAttempt;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionWorkerTest {

    @Mock
    private ExecutionQueue queue;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WorkflowExecutionValidator validator;

    @Mock
    private WorkflowExecutionEngine engine;

    @Mock
    private MongoTemplate mongoTemplate;

    private ExecutionWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ExecutionWorker(
                queue,
                workflowRepository,
                executionRepository,
                validator,
                engine,
                mongoTemplate,
                false, // disabled by default in test to avoid background loop
                2000
        );
    }

    @Test
    void processJob_ExecutionNotFound_SkipsWithoutCrashing() {
        ExecutionJob job = new ExecutionJob("exec-missing", "wf-1", "user-1", "manual", Instant.now());
        when(executionRepository.findById("exec-missing")).thenReturn(Optional.empty());

        boolean result = worker.processJob(job);

        assertFalse(result);
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_AlreadyRunning_SkipsDuplicateExecution() {
        ExecutionJob job = new ExecutionJob("exec-running", "wf-1", "user-1", "manual", Instant.now());
        WorkflowExecution exec = new WorkflowExecution("exec-running", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);
        when(executionRepository.findById("exec-running")).thenReturn(Optional.of(exec));

        boolean result = worker.processJob(job);

        assertFalse(result);
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_AlreadyCompletedSuccess_SkipsDuplicateExecution() {
        ExecutionJob job = new ExecutionJob("exec-success", "wf-1", "user-1", "manual", Instant.now());
        WorkflowExecution exec = new WorkflowExecution("exec-success", "wf-1", "user-1",
                ExecutionStatus.SUCCESS, "manual", Instant.now(), Instant.now(), 50L, List.of(), null);
        when(executionRepository.findById("exec-success")).thenReturn(Optional.of(exec));

        boolean result = worker.processJob(job);

        assertFalse(result);
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_ConcurrentWorkerClaimsFirst_SkipsGracefully() {
        ExecutionJob job = new ExecutionJob("exec-queued", "wf-1", "user-1", "manual", Instant.now());
        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-queued");

        when(executionRepository.findById("exec-queued")).thenReturn(Optional.of(queuedExec));
        // Atomic findAndModify returns null when another worker modified status concurrently
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(null);

        boolean result = worker.processJob(job);

        assertFalse(result);
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_WorkflowNotFound_MarksExecutionFailed() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-missing", "user-1", "manual", Instant.now());
        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-missing", "user-1", "manual");
        queuedExec.setId("exec-1");

        WorkflowExecution claimed = WorkflowExecution.queued("wf-missing", "user-1", "manual");
        claimed.setId("exec-1");
        claimed.markRunning(Instant.now());

        when(executionRepository.findById("exec-1")).thenReturn(Optional.of(queuedExec));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(claimed);
        when(workflowRepository.findById("wf-missing")).thenReturn(Optional.empty());

        boolean result = worker.processJob(job);

        assertTrue(result);
        assertEquals(ExecutionStatus.FAILED, claimed.getStatus());
        assertTrue(claimed.getError().contains("Workflow not found"));
        verify(executionRepository).save(claimed);
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_ValidationFails_MarksExecutionFailed() {
        ExecutionJob job = new ExecutionJob("exec-1", "wf-invalid", "user-1", "manual", Instant.now());
        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-invalid", "user-1", "manual");
        queuedExec.setId("exec-1");

        WorkflowExecution claimed = WorkflowExecution.queued("wf-invalid", "user-1", "manual");
        claimed.setId("exec-1");
        claimed.markRunning(Instant.now());

        Workflow workflow = new Workflow("wf-invalid", "user-1", "Invalid WF", null, null, List.of(), List.of(), null, null);

        when(executionRepository.findById("exec-1")).thenReturn(Optional.of(queuedExec));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(claimed);
        when(workflowRepository.findById("wf-invalid")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenThrow(new com.adonis.exception.WorkflowValidationException("Cycle detected"));

        boolean result = worker.processJob(job);

        assertTrue(result);
        assertEquals(ExecutionStatus.FAILED, claimed.getStatus());
        assertTrue(claimed.getError().contains("Workflow validation failed: Cycle detected"));
        verify(executionRepository).save(claimed);
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_EngineSuccess_PersistsSuccessWithDurationAndNodes() {
        Instant startTime = Instant.now().minusMillis(150);
        ExecutionJob job = new ExecutionJob("exec-success-job", "wf-1", "user-1", "manual", startTime);

        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-success-job");

        WorkflowExecution claimed = WorkflowExecution.queued("wf-1", "user-1", "manual");
        claimed.setId("exec-success-job");
        claimed.markRunning(startTime);

        WorkflowNode node1 = new WorkflowNode("node-1", "trigger", Map.of("triggerType", "manual"));
        Workflow workflow = new Workflow("wf-1", "user-1", "Test WF", null, null, List.of(node1), List.of(), null, null);

        Instant completedTime = Instant.now();
        WorkflowExecutionResult engineResult = WorkflowExecutionResult.success(
                "exec-success-job",
                "wf-1",
                startTime,
                completedTime,
                List.of(NodeExecutionResult.success("node-1", "trigger", startTime, completedTime, Map.of("key", "val")))
        );

        when(executionRepository.findById("exec-success-job")).thenReturn(Optional.of(queuedExec));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(claimed);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node1));
        when(engine.execute(workflow, List.of(node1), "user-1", "exec-success-job")).thenReturn(engineResult);

        boolean result = worker.processJob(job);

        assertTrue(result);
        assertEquals(ExecutionStatus.SUCCESS, claimed.getStatus());
        assertNotNull(claimed.getCompletedAt());
        assertNotNull(claimed.getDurationMs());
        assertEquals(1, claimed.getNodeExecutions().size());
        assertEquals("node-1", claimed.getNodeExecutions().get(0).getNodeId());
        assertNull(claimed.getError());
        verify(executionRepository).save(claimed);
    }

    @Test
    void processJob_EngineFailure_PersistsFailureWithSanitizedError() {
        Instant startTime = Instant.now().minusMillis(100);
        ExecutionJob job = new ExecutionJob("exec-fail-job", "wf-1", "user-1", "manual", startTime);

        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-fail-job");

        WorkflowExecution claimed = WorkflowExecution.queued("wf-1", "user-1", "manual");
        claimed.setId("exec-fail-job");
        claimed.markRunning(startTime);

        WorkflowNode node1 = new WorkflowNode("node-1", "trigger", Map.of("triggerType", "manual"));
        Workflow workflow = new Workflow("wf-1", "user-1", "Test WF", null, null, List.of(node1), List.of(), null, null);

        Instant completedTime = Instant.now();
        WorkflowExecutionResult engineResult = WorkflowExecutionResult.failure(
                "exec-fail-job",
                "wf-1",
                startTime,
                completedTime,
                List.of(NodeExecutionResult.failure("node-1", "trigger", startTime, completedTime, Map.of(), "HTTP 500 error")),
                "Step node-1 failed with: HTTP 500 error"
        );

        when(executionRepository.findById("exec-fail-job")).thenReturn(Optional.of(queuedExec));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(claimed);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node1));
        when(engine.execute(workflow, List.of(node1), "user-1", "exec-fail-job")).thenReturn(engineResult);

        boolean result = worker.processJob(job);

        assertTrue(result);
        assertEquals(ExecutionStatus.FAILED, claimed.getStatus());
        assertEquals("Step node-1 failed with: HTTP 500 error", claimed.getError());
        verify(executionRepository).save(claimed);
    }

    @Test
    void processJob_UnexpectedEngineException_CatchesAndPersistsFailedWithoutCrashing() {
        ExecutionJob job = new ExecutionJob("exec-crash", "wf-1", "user-1", "manual", Instant.now());

        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-crash");

        WorkflowExecution claimed = WorkflowExecution.queued("wf-1", "user-1", "manual");
        claimed.setId("exec-crash");
        claimed.markRunning(Instant.now());

        WorkflowNode node1 = new WorkflowNode("node-1", "trigger", Map.of());
        Workflow workflow = new Workflow("wf-1", "user-1", "Test WF", null, null, List.of(node1), List.of(), null, null);

        when(executionRepository.findById("exec-crash")).thenReturn(Optional.of(queuedExec));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(claimed);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node1));
        when(engine.execute(any(), any(), any(), any())).thenThrow(new NullPointerException("Unexpected engine NPE"));

        boolean result = worker.processJob(job);

        assertTrue(result);
        assertEquals(ExecutionStatus.FAILED, claimed.getStatus());
        assertTrue(claimed.getError().contains("Execution engine failure"));
        verify(executionRepository).save(claimed);
    }

    @Test
    void processJob_Phase6RetryCompatibility_PersistsAttemptsAndRetryCount() {
        Instant startTime = Instant.now().minusMillis(200);
        ExecutionJob job = new ExecutionJob("exec-retried", "wf-1", "user-1", "manual", startTime);

        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-retried");

        WorkflowExecution claimed = WorkflowExecution.queued("wf-1", "user-1", "manual");
        claimed.setId("exec-retried");
        claimed.markRunning(startTime);

        WorkflowNode node1 = new WorkflowNode("node-1", "httpRequest", Map.of());
        Workflow workflow = new Workflow("wf-1", "user-1", "Retry WF", null, null, List.of(node1), List.of(), null, null);

        NodeExecutionAttempt attempt1 = new NodeExecutionAttempt(1, ExecutionStatus.FAILED, startTime, startTime.plusMillis(50), 50L, Map.of(), Map.of(), "HTTP 503");
        NodeExecutionAttempt attempt2 = new NodeExecutionAttempt(2, ExecutionStatus.SUCCESS, startTime.plusMillis(100), startTime.plusMillis(150), 50L, Map.of(), Map.of("res", "ok"), null);

        NodeExecutionResult nodeResult = new NodeExecutionResult(
                "node-1",
                "httpRequest",
                ExecutionStatus.SUCCESS,
                startTime,
                startTime.plusMillis(150),
                150L,
                Map.of(),
                Map.of("res", "ok"),
                null,
                1,
                List.of(attempt1, attempt2)
        );

        WorkflowExecutionResult engineResult = WorkflowExecutionResult.success(
                "exec-retried",
                "wf-1",
                startTime,
                startTime.plusMillis(150),
                List.of(nodeResult)
        );

        when(executionRepository.findById("exec-retried")).thenReturn(Optional.of(queuedExec));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(claimed);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node1));
        when(engine.execute(workflow, List.of(node1), "user-1", "exec-retried")).thenReturn(engineResult);

        boolean result = worker.processJob(job);

        assertTrue(result);
        assertEquals(ExecutionStatus.SUCCESS, claimed.getStatus());
        assertEquals(1, claimed.getNodeExecutions().size());
        NodeExecution persistedNode = claimed.getNodeExecutions().get(0);
        assertEquals(1, persistedNode.getRetryCount());
        assertEquals(2, persistedNode.getAttempts().size());
        assertEquals(1, persistedNode.getAttempts().get(0).getAttemptNumber());
        assertEquals(2, persistedNode.getAttempts().get(1).getAttemptNumber());
        verify(executionRepository).save(claimed);
    }
}
