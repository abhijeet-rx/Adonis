package com.adonis.queue;

import com.adonis.exception.WorkflowValidationException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private static final long STALE_TIMEOUT_MS = 60000L; // 60 seconds for test

    @BeforeEach
    void setUp() {
        worker = new ExecutionWorker(
                queue,
                workflowRepository,
                executionRepository,
                validator,
                engine,
                mongoTemplate,
                false, // disabled background thread for deterministic tests
                2000L,
                10000L,
                STALE_TIMEOUT_MS,
                "test-worker"
        );
    }

    @Test
    void processJob_ExecutionNotFound_AcknowledgesOrphanAndSkipsWithoutCrashing() {
        QueuedJobMessage message = new QueuedJobMessage("msg-1",
                new ExecutionJob("exec-missing", "wf-1", "user-1", "manual", Instant.now()));
        when(executionRepository.findById("exec-missing")).thenReturn(Optional.empty());

        boolean result = worker.processJob(message);

        assertFalse(result);
        verify(queue).acknowledge("msg-1");
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_AlreadyRunning_ValidLease_SkipsWithoutExecutingOrAcknowledging() {
        // Started 10 seconds ago with a valid lease until 50 seconds in the future
        Instant recentStart = Instant.now().minus(10, ChronoUnit.SECONDS);
        WorkflowExecution exec = new WorkflowExecution("exec-running", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", recentStart, null, null, List.of(), null);
        exec.setWorkerId("worker-active");
        exec.setLeaseUntil(Instant.now().plus(50, ChronoUnit.SECONDS));
        when(executionRepository.findById("exec-running")).thenReturn(Optional.of(exec));

        QueuedJobMessage message = new QueuedJobMessage("msg-2",
                new ExecutionJob("exec-running", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertFalse(result);
        verify(queue, never()).acknowledge(anyString());
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_ActiveLongRunningWorkflow_ValidLease_NotFailedAndNotReExecuted() {
        // Critical test: Workflow started 15 minutes ago (longer than old 5-minute timeout),
        // but owner worker is actively renewing its lease (leaseUntil is 40 seconds in the future)
        Instant longAgoStart = Instant.now().minus(15, ChronoUnit.MINUTES);
        WorkflowExecution exec = new WorkflowExecution("exec-long-running", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", longAgoStart, null, null, List.of(), null);
        exec.setWorkerId("worker-active-long");
        exec.setLeaseUntil(Instant.now().plus(40, ChronoUnit.SECONDS));
        when(executionRepository.findById("exec-long-running")).thenReturn(Optional.of(exec));

        QueuedJobMessage message = new QueuedJobMessage("msg-long-running",
                new ExecutionJob("exec-long-running", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        // MUST NOT be marked failed, MUST NOT re-execute, MUST NOT ACK
        assertFalse(result);
        assertEquals(ExecutionStatus.RUNNING, exec.getStatus());
        verify(executionRepository, never()).save(any());
        verify(queue, never()).acknowledge(anyString());
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_AlreadyRunning_ExpiredLease_AtomicTakeoverWins_MarksFailedAndAcknowledges() {
        // Started 5 minutes ago with expired lease (lease expired 30 seconds ago)
        Instant staleStart = Instant.now().minus(300, ChronoUnit.SECONDS);
        WorkflowExecution exec = new WorkflowExecution("exec-stale", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", staleStart, null, null, List.of(), null);
        exec.setWorkerId("worker-crashed");
        exec.setLeaseUntil(Instant.now().minus(30, ChronoUnit.SECONDS));
        when(executionRepository.findById("exec-stale")).thenReturn(Optional.of(exec));

        // Atomic takeover succeeds
        WorkflowExecution acquired = new WorkflowExecution("exec-stale", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", staleStart, null, null, List.of(), null);
        acquired.setWorkerId("test-worker");
        acquired.setLeaseUntil(Instant.now().plus(60, ChronoUnit.SECONDS));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(acquired);

        QueuedJobMessage message = new QueuedJobMessage("msg-stale",
                new ExecutionJob("exec-stale", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertFalse(result);
        // Marked FAILED in repository with diagnostic reason
        assertEquals(ExecutionStatus.FAILED, acquired.getStatus());
        assertTrue(acquired.getError().contains("Execution lease expired"));
        assertTrue(acquired.getError().contains("worker-crashed"));
        verify(executionRepository).save(acquired);
        // Acknowledged from Redis stream
        verify(queue).acknowledge("msg-stale");
        // No duplicate node execution attempted!
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_AlreadyRunning_ExpiredLease_AtomicTakeoverLosesRace_DoesNothing() {
        // Lease expired, but another worker races and claims it first
        WorkflowExecution exec = new WorkflowExecution("exec-stale-race", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now().minus(300, ChronoUnit.SECONDS), null, null, List.of(), null);
        exec.setWorkerId("worker-crashed");
        exec.setLeaseUntil(Instant.now().minus(30, ChronoUnit.SECONDS));
        when(executionRepository.findById("exec-stale-race")).thenReturn(Optional.of(exec));

        // findAndModify returns null when another worker raced and took over
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(null);

        QueuedJobMessage message = new QueuedJobMessage("msg-stale-race",
                new ExecutionJob("exec-stale-race", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertFalse(result);
        verify(executionRepository, never()).save(any());
        verify(queue, never()).acknowledge("msg-stale-race");
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_LeaseAcquisition_SetsWorkerIdAndLeaseMetadata() {
        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-lease");
        when(executionRepository.findById("exec-lease")).thenReturn(Optional.of(queuedExec));

        WorkflowExecution runningExec = new WorkflowExecution("exec-lease", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);
        runningExec.setWorkerId("test-worker");
        runningExec.setLeaseUntil(Instant.now().plus(60, ChronoUnit.SECONDS));

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        when(mongoTemplate.findAndModify(any(Query.class), updateCaptor.capture(), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(runningExec);

        WorkflowNode node = new WorkflowNode("node-1", "trigger", Map.of());
        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, null, List.of(node), List.of(), null, null);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node));
        when(engine.execute(any(), any(), any(), any())).thenReturn(WorkflowExecutionResult.success(
                "exec-lease", "wf-1", Instant.now().minusMillis(10), Instant.now(), List.of()
        ));

        QueuedJobMessage message = new QueuedJobMessage("msg-lease",
                new ExecutionJob("exec-lease", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertTrue(result);
        Update capturedUpdate = updateCaptor.getValue();
        org.bson.Document updateDoc = capturedUpdate.getUpdateObject();
        org.bson.Document setDoc = (org.bson.Document) updateDoc.get("$set");
        assertNotNull(setDoc);
        assertEquals(ExecutionStatus.RUNNING, setDoc.get("status"));
        assertEquals("test-worker", setDoc.get("workerId"));
        assertNotNull(setDoc.get("leaseUntil"));
        assertNotNull(setDoc.get("lastHeartbeatAt"));
        assertNotNull(setDoc.get("startedAt"));
    }

    @Test
    void renewLease_ActiveExecution_ExtendsLeaseUntilAndHeartbeat() {
        com.mongodb.client.result.UpdateResult mockResult = mock(com.mongodb.client.result.UpdateResult.class);
        when(mockResult.getMatchedCount()).thenReturn(1L);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        when(mongoTemplate.updateFirst(queryCaptor.capture(), updateCaptor.capture(), eq(WorkflowExecution.class)))
                .thenReturn(mockResult);

        boolean renewed = worker.renewLease("exec-renew-1");

        assertTrue(renewed);
        Query capturedQuery = queryCaptor.getValue();
        org.bson.Document queryDoc = capturedQuery.getQueryObject();
        assertEquals("exec-renew-1", queryDoc.get("_id"));
        assertEquals(ExecutionStatus.RUNNING, queryDoc.get("status"));
        assertEquals("test-worker", queryDoc.get("workerId"));

        Update capturedUpdate = updateCaptor.getValue();
        org.bson.Document setDoc = (org.bson.Document) capturedUpdate.getUpdateObject().get("$set");
        assertNotNull(setDoc.get("leaseUntil"));
        assertNotNull(setDoc.get("lastHeartbeatAt"));
    }

    @Test
    void renewLease_OwnershipSafety_FailsWhenWorkerDoesNotOwnExecution() {
        // When the worker does not own the execution, matchedCount = 0
        com.mongodb.client.result.UpdateResult mockResult = mock(com.mongodb.client.result.UpdateResult.class);
        when(mockResult.getMatchedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(WorkflowExecution.class)))
                .thenReturn(mockResult);

        boolean renewed = worker.renewLease("exec-not-owned");

        // Renewal must fail safely
        assertFalse(renewed);
    }

    @Test
    void renewLease_TransientMongoFailure_ToleratedWithoutThrowing() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(WorkflowExecution.class)))
                .thenThrow(new RuntimeException("MongoDB transient network glitch"));

        // Must not throw an unhandled exception
        boolean renewed = worker.renewLease("exec-glitch");

        assertFalse(renewed);
    }

    @Test
    void processJob_AlreadyCompletedSuccess_AcknowledgesDuplicateWithoutReExecuting() {
        WorkflowExecution exec = new WorkflowExecution("exec-success", "wf-1", "user-1",
                ExecutionStatus.SUCCESS, "manual", Instant.now(), Instant.now(), 50L, List.of(), null);
        when(executionRepository.findById("exec-success")).thenReturn(Optional.of(exec));

        QueuedJobMessage message = new QueuedJobMessage("msg-dup-success",
                new ExecutionJob("exec-success", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertFalse(result);
        verify(queue).acknowledge("msg-dup-success");
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_AlreadyCompletedFailed_AcknowledgesDuplicateWithoutReExecuting() {
        WorkflowExecution exec = new WorkflowExecution("exec-failed", "wf-1", "user-1",
                ExecutionStatus.FAILED, "manual", Instant.now(), Instant.now(), 50L, List.of(), "Prior error");
        when(executionRepository.findById("exec-failed")).thenReturn(Optional.of(exec));

        QueuedJobMessage message = new QueuedJobMessage("msg-dup-failed",
                new ExecutionJob("exec-failed", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertFalse(result);
        verify(queue).acknowledge("msg-dup-failed");
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(WorkflowExecution.class));
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_ConcurrentWorkerClaimsFirst_SkipsGracefully() {
        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-queued");
        when(executionRepository.findById("exec-queued")).thenReturn(Optional.of(queuedExec));

        // Atomic findAndModify returns null when another worker raced and claimed it first
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(null);

        QueuedJobMessage message = new QueuedJobMessage("msg-race",
                new ExecutionJob("exec-queued", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertFalse(result);
        verify(queue, never()).acknowledge("msg-race");
        verify(engine, never()).execute(any(), any(), any(), any());
    }

    @Test
    void processJob_WorkflowNotFound_MarksExecutionFailedAndAcknowledges() {
        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-missing", "user-1", "manual");
        queuedExec.setId("exec-wf-missing");
        when(executionRepository.findById("exec-wf-missing")).thenReturn(Optional.of(queuedExec));

        WorkflowExecution runningExec = new WorkflowExecution("exec-wf-missing", "wf-missing", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(runningExec);

        when(workflowRepository.findById("wf-missing")).thenReturn(Optional.empty());

        QueuedJobMessage message = new QueuedJobMessage("msg-wf-missing",
                new ExecutionJob("exec-wf-missing", "wf-missing", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertTrue(result);
        assertEquals(ExecutionStatus.FAILED, runningExec.getStatus());
        assertTrue(runningExec.getError().contains("Workflow not found with id: wf-missing"));
        verify(executionRepository).save(runningExec);
        verify(queue).acknowledge("msg-wf-missing");
    }

    @Test
    void processJob_ValidationFails_MarksExecutionFailedAndAcknowledges() {
        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-cycle", "user-1", "manual");
        queuedExec.setId("exec-cycle");
        when(executionRepository.findById("exec-cycle")).thenReturn(Optional.of(queuedExec));

        WorkflowExecution runningExec = new WorkflowExecution("exec-cycle", "wf-cycle", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(runningExec);

        Workflow workflow = new Workflow("wf-cycle", "user-1", "Cycle WF", null, null, List.of(), List.of(), null, null);
        when(workflowRepository.findById("wf-cycle")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenThrow(new WorkflowValidationException("Cycle detected"));

        QueuedJobMessage message = new QueuedJobMessage("msg-cycle",
                new ExecutionJob("exec-cycle", "wf-cycle", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertTrue(result);
        assertEquals(ExecutionStatus.FAILED, runningExec.getStatus());
        assertTrue(runningExec.getError().contains("Workflow validation failed: Cycle detected"));
        verify(executionRepository).save(runningExec);
        verify(queue).acknowledge("msg-cycle");
    }

    @Test
    void processJob_EngineFailure_MarksExecutionFailedSanitizesErrorAndAcknowledges() {
        WorkflowNode node = new WorkflowNode("node-1", "http", Map.of());
        Workflow workflow = new Workflow("wf-fail", "user-1", "Fail WF", null, null, List.of(node), List.of(), null, null);
        WorkflowExecution runningExec = new WorkflowExecution("exec-fail", "wf-fail", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);

        when(executionRepository.findById("exec-fail")).thenReturn(Optional.of(WorkflowExecution.queued("wf-fail", "user-1", "manual")));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(runningExec);
        when(workflowRepository.findById("wf-fail")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node));

        WorkflowExecutionResult failureResult = WorkflowExecutionResult.failure(
                "exec-fail",
                "wf-fail",
                Instant.now().minusMillis(50),
                Instant.now(),
                List.of(new NodeExecutionResult("node-1", "http", ExecutionStatus.FAILED,
                        Instant.now().minusMillis(50), Instant.now(), 50L, Map.of(), Map.of(), "Bearer secret-token 500 error", 1, List.of())),
                "Workflow failed: Bearer secret-token 500 error"
        );
        when(engine.execute(eq(workflow), any(), eq("user-1"), eq("exec-fail"))).thenReturn(failureResult);

        QueuedJobMessage message = new QueuedJobMessage("msg-fail",
                new ExecutionJob("exec-fail", "wf-fail", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertTrue(result);
        assertEquals(ExecutionStatus.FAILED, runningExec.getStatus());
        // Verify sanitization
        assertFalse(runningExec.getError().contains("secret-token"));
        assertTrue(runningExec.getError().contains("[REDACTED]"));
        verify(executionRepository).save(runningExec);
        verify(queue).acknowledge("msg-fail");
    }

    @Test
    void processJob_PersistenceFailure_DoesNotAcknowledgeMessage() {
        WorkflowNode node = new WorkflowNode("node-1", "trigger", Map.of());
        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, null, List.of(node), List.of(), null, null);
        WorkflowExecution runningExec = new WorkflowExecution("exec-persist-fail", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);

        when(executionRepository.findById("exec-persist-fail")).thenReturn(Optional.of(WorkflowExecution.queued("wf-1", "user-1", "manual")));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(runningExec);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node));

        WorkflowExecutionResult successResult = WorkflowExecutionResult.success(
                "exec-persist-fail",
                "wf-1",
                Instant.now().minusMillis(20),
                Instant.now(),
                List.of(new NodeExecutionResult("node-1", "trigger", ExecutionStatus.SUCCESS,
                        Instant.now().minusMillis(20), Instant.now(), 20L, Map.of(), Map.of(), null, 0, List.of()))
        );
        when(engine.execute(any(), any(), any(), any())).thenReturn(successResult);

        // MongoDB save throws exception
        doThrow(new RuntimeException("MongoDB network timeout")).when(executionRepository).save(any(WorkflowExecution.class));

        QueuedJobMessage message = new QueuedJobMessage("msg-persist-fail",
                new ExecutionJob("exec-persist-fail", "wf-1", "user-1", "manual", Instant.now()));

        assertThrows(RuntimeException.class, () -> worker.processJob(message));

        // CRITICAL: Must NOT acknowledge message when persistence failed!
        verify(queue, never()).acknowledge("msg-persist-fail");
    }

    @Test
    void processJob_Success_PersistsSuccessAndAcknowledgesOnlyAfterPersistence() {
        WorkflowNode node = new WorkflowNode("node-1", "trigger", Map.of());
        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, null, List.of(node), List.of(), null, null);
        WorkflowExecution runningExec = new WorkflowExecution("exec-success", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);

        when(executionRepository.findById("exec-success")).thenReturn(Optional.of(WorkflowExecution.queued("wf-1", "user-1", "manual")));
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(runningExec);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node));

        WorkflowExecutionResult successResult = WorkflowExecutionResult.success(
                "exec-success",
                "wf-1",
                Instant.now().minusMillis(30),
                Instant.now(),
                List.of(new NodeExecutionResult("node-1", "trigger", ExecutionStatus.SUCCESS,
                        Instant.now().minusMillis(30), Instant.now(), 30L, Map.of(), Map.of(), null, 0, List.of()))
        );
        when(engine.execute(eq(workflow), any(), eq("user-1"), eq("exec-success"))).thenReturn(successResult);

        QueuedJobMessage message = new QueuedJobMessage("msg-success-1",
                new ExecutionJob("exec-success", "wf-1", "user-1", "manual", Instant.now()));

        boolean result = worker.processJob(message);

        assertTrue(result);
        assertEquals(ExecutionStatus.SUCCESS, runningExec.getStatus());
        verify(executionRepository).save(runningExec);
        // Acknowledged after save
        verify(queue).acknowledge("msg-success-1");
    }

    @Test
    void recoverPendingJobs_ClaimsAndProcessesUnacknowledgedJobs() {
        QueuedJobMessage recoveredMsg = new QueuedJobMessage("rec-1",
                new ExecutionJob("exec-rec", "wf-1", "user-1", "manual", Instant.now()), 2);

        when(queue.claimPending(any(java.time.Duration.class), eq(10))).thenReturn(List.of(recoveredMsg));

        WorkflowExecution queuedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        queuedExec.setId("exec-rec");
        when(executionRepository.findById("exec-rec")).thenReturn(Optional.of(queuedExec));

        WorkflowExecution runningExec = new WorkflowExecution("exec-rec", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now(), null, null, List.of(), null);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(WorkflowExecution.class))).thenReturn(runningExec);

        WorkflowNode node = new WorkflowNode("node-1", "trigger", Map.of());
        Workflow workflow = new Workflow("wf-1", "user-1", "WF 1", null, null, List.of(node), List.of(), null, null);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node));
        when(engine.execute(any(), any(), any(), any())).thenReturn(WorkflowExecutionResult.success(
                "exec-rec", "wf-1", Instant.now().minusMillis(10), Instant.now(), List.of()
        ));

        worker.recoverPendingJobs();

        verify(queue).claimPending(any(java.time.Duration.class), eq(10));
        verify(executionRepository).save(runningExec);
        verify(queue).acknowledge("rec-1");
    }

    @Test
    void twoWorkers_ConcurrentTakeover_OnlyOneWinsAndMarksFailed() {
        ExecutionWorker workerA = new ExecutionWorker(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                false, 2000L, 10000L, 60000L, 20000L, "worker-A");
        ExecutionWorker workerB = new ExecutionWorker(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                false, 2000L, 10000L, 60000L, 20000L, "worker-B");

        WorkflowExecution staleExec = new WorkflowExecution("exec-concurrent-stale", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now().minus(300, ChronoUnit.SECONDS), null, null, List.of(), null);
        staleExec.setWorkerId("worker-dead");
        staleExec.setLeaseUntil(Instant.now().minus(20, ChronoUnit.SECONDS));

        when(executionRepository.findById("exec-concurrent-stale")).thenReturn(Optional.of(staleExec));

        WorkflowExecution acquiredByA = new WorkflowExecution("exec-concurrent-stale", "wf-1", "user-1",
                ExecutionStatus.RUNNING, "manual", Instant.now().minus(300, ChronoUnit.SECONDS), null, null, List.of(), null);
        acquiredByA.setWorkerId("worker-A");
        acquiredByA.setLeaseUntil(Instant.now().plus(60, ChronoUnit.SECONDS));

        // workerA succeeds in findAndModify; workerB gets null
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(WorkflowExecution.class)))
                .thenReturn(acquiredByA)
                .thenReturn(null);

        QueuedJobMessage msgA = new QueuedJobMessage("msg-takeover-A",
                new ExecutionJob("exec-concurrent-stale", "wf-1", "user-1", "manual", Instant.now()));
        QueuedJobMessage msgB = new QueuedJobMessage("msg-takeover-B",
                new ExecutionJob("exec-concurrent-stale", "wf-1", "user-1", "manual", Instant.now()));

        boolean resA = workerA.processJob(msgA);
        boolean resB = workerB.processJob(msgB);

        // workerA processed the recovery
        assertFalse(resA);
        assertEquals(ExecutionStatus.FAILED, acquiredByA.getStatus());
        verify(executionRepository, times(1)).save(acquiredByA);
        verify(queue).acknowledge("msg-takeover-A");

        // workerB was safely locked out
        assertFalse(resB);
        verify(queue, never()).acknowledge("msg-takeover-B");
    }

    @Test
    void oldWorker_HeartbeatAfterTakeover_FailsAndDoesNotOverwriteTakeoverState() {
        ExecutionWorker workerA = new ExecutionWorker(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                false, 2000L, 10000L, 60000L, 20000L, "worker-A");

        // When workerA attempts to renew lease, MongoDB query matches 0 documents because workerId is now worker-B
        com.mongodb.client.result.UpdateResult zeroMatched = mock(com.mongodb.client.result.UpdateResult.class);
        when(zeroMatched.getMatchedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(WorkflowExecution.class)))
                .thenReturn(zeroMatched);

        boolean renewed = workerA.renewLease("exec-taken-over");

        assertFalse(renewed);
    }

    @Test
    void worker_UniqueIdentityGenerated_WhenConsumerNameIsBlank() {
        ExecutionWorker defaultWorker1 = new ExecutionWorker(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                false, 2000L, 10000L, 60000L, 20000L, "");
        ExecutionWorker defaultWorker2 = new ExecutionWorker(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                false, 2000L, 10000L, 60000L, 20000L, null);

        assertNotNull(defaultWorker1.getWorkerId());
        assertTrue(defaultWorker1.getWorkerId().startsWith("worker-"));
        assertNotEquals("worker-default", defaultWorker1.getWorkerId());

        assertNotNull(defaultWorker2.getWorkerId());
        assertTrue(defaultWorker2.getWorkerId().startsWith("worker-"));

        // Must be unique between distinct instances
        assertNotEquals(defaultWorker1.getWorkerId(), defaultWorker2.getWorkerId());
    }

    @Test
    void worker_ClampsHeartbeat_WhenHeartbeatGreaterOrEqualToLeaseDuration() {
        ExecutionWorker clampedWorker = new ExecutionWorker(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                false, 2000L, 10000L, 30000L, 45000L, "test-clamped");

        // Heartbeat was configured at 45000ms which is >= leaseDuration (30000ms)
        // Must clamp to leaseDuration / 3 = 10000ms
        assertEquals(30000L, clampedWorker.getLeaseDurationMs());
        assertEquals(10000L, clampedWorker.getHeartbeatIntervalMs());
    }
}
