package com.adonis.integration.queue;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.queue.ExecutionJob;
import com.adonis.queue.ExecutionWorker;
import com.adonis.queue.QueuedJobMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Worker Lease Integration Tests (Real MongoDB + Redis)")
class WorkerLeaseIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("Claim: transition from QUEUED to RUNNING sets workerId, leaseUntil, and lastHeartbeatAt")
    void claim_TransitionToRunning_SetsWorkerLeaseMetadata() {
        mockHttpServer.setDefaultHttpResponse(200, "{\"ok\":true}");

        Workflow workflow = TestDataFactory.createHttpWorkflow("user-lease", "Lease WF", mockHttpServer.getHttpEndpointUrl(), "GET");
        workflow = workflowRepository.save(workflow);

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), "user-lease", "MANUAL");
        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        QueuedJobMessage message = new QueuedJobMessage("msg-lease-claim",
                new ExecutionJob(executionId, workflow.getId(), "user-lease", "MANUAL", Instant.now()));

        boolean processed = worker.processJob(message);
        assertTrue(processed);

        WorkflowExecution completed = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());
        assertNotNull(completed.getWorkerId());
        assertNotNull(completed.getLeaseUntil());
        assertNotNull(completed.getLastHeartbeatAt());
    }

    @Test
    @DisplayName("Heartbeat: renewLease extends leaseUntil and lastHeartbeatAt in real MongoDB")
    void heartbeat_RenewLease_ExtendsLeaseUntilAndLastHeartbeat() {
        Instant startTime = Instant.now().minusSeconds(10);
        Instant initialLease = startTime.plusSeconds(5);

        WorkflowExecution execution = new WorkflowExecution("exec-heartbeat-1", "wf-hb", "user-hb",
                ExecutionStatus.RUNNING, "MANUAL", startTime, null, null, java.util.List.of(), null);
        execution.setWorkerId(worker.getWorkerId());
        execution.setLeaseUntil(initialLease);
        execution.setLastHeartbeatAt(startTime);
        executionRepository.save(execution);

        // Renew lease via worker
        boolean renewed = worker.renewLease("exec-heartbeat-1");
        assertTrue(renewed, "Worker must successfully renew lease for an execution it owns");

        WorkflowExecution updated = executionRepository.findById("exec-heartbeat-1").orElseThrow();
        assertTrue(updated.getLeaseUntil().isAfter(initialLease), "leaseUntil must be extended into the future");
        assertTrue(updated.getLastHeartbeatAt().isAfter(startTime), "lastHeartbeatAt must be updated");
    }

    @Test
    @DisplayName("Ownership: worker cannot renew lease or modify execution after losing ownership")
    void ownership_WorkerCannotRenewLease_AfterLosingOwnership() {
        // Execution is owned by "another-worker", not worker.getWorkerId()
        WorkflowExecution execution = new WorkflowExecution("exec-other-worker", "wf-other", "user-1",
                ExecutionStatus.RUNNING, "MANUAL", Instant.now(), null, null, java.util.List.of(), null);
        execution.setWorkerId("another-worker-id");
        execution.setLeaseUntil(Instant.now().plusSeconds(60));
        execution.setLastHeartbeatAt(Instant.now());
        executionRepository.save(execution);

        // Current worker attempts to renew lease
        boolean renewed = worker.renewLease("exec-other-worker");
        assertFalse(renewed, "Worker must fail to renew lease when it is not the current owner");

        // Verify document in MongoDB was not modified by current worker
        WorkflowExecution unmodified = executionRepository.findById("exec-other-worker").orElseThrow();
        assertEquals("another-worker-id", unmodified.getWorkerId());
    }

    @Test
    @DisplayName("Expired lease: worker B takes over expired lease from crashed worker A, marks FAILED, and ACKs")
    void expiredLease_WorkerBTakesOverFromCrashedWorkerA() {
        // Simulate crashed worker A: lease expired 30 seconds ago
        Instant crashedStart = Instant.now().minus(300, ChronoUnit.SECONDS);
        Instant expiredLease = Instant.now().minus(30, ChronoUnit.SECONDS);

        WorkflowExecution stale = new WorkflowExecution("exec-crashed-worker", "wf-stale", "user-1",
                ExecutionStatus.RUNNING, "MANUAL", crashedStart, null, null, java.util.List.of(), null);
        stale.setWorkerId("crashed-worker-A");
        stale.setLeaseUntil(expiredLease);
        stale.setLastHeartbeatAt(crashedStart);
        executionRepository.save(stale);

        // Put message in queue for Worker B to process
        ExecutionJob job = new ExecutionJob("exec-crashed-worker", "wf-stale", "user-1", "MANUAL", Instant.now());
        queue.enqueue(job);

        java.util.Optional<QueuedJobMessage> msgOpt = queue.poll(java.time.Duration.ofSeconds(2));
        assertTrue(msgOpt.isPresent());

        // Worker B processes the message
        boolean processed = worker.processJob(msgOpt.get());
        // Worker B performed recovery takeover without re-executing nodes (returns false for recovery skip)
        assertFalse(processed);

        WorkflowExecution recovered = executionRepository.findById("exec-crashed-worker").orElseThrow();
        assertEquals(ExecutionStatus.FAILED, recovered.getStatus(), "Recovered stale execution must be marked FAILED");
        assertEquals(worker.getWorkerId(), recovered.getWorkerId(), "Owner worker must be updated to Worker B");
        assertTrue(recovered.getError().contains("Execution lease expired"), "Diagnostic recovery error must be recorded");
        assertTrue(recovered.getError().contains("crashed-worker-A"));
    }
}
