package com.adonis.queue;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.exception.WorkflowValidationException;
import com.adonis.execution.*;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionQueueingTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionValidator validator;

    @Mock
    private WorkflowExecutionEngine engine;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private ExecutionQueue executionQueue;

    private WorkflowExecutionService executionService;

    @BeforeEach
    void setUp() {
        executionService = new WorkflowExecutionService(
                workflowRepository,
                validator,
                engine,
                executionRepository,
                executionQueue
        );
    }

    @Test
    void enqueueExecution_OwnershipMismatch_ThrowsWorkflowNotFoundException() {
        when(workflowRepository.findByIdAndUserId("wf-100", "user-attacker")).thenReturn(Optional.empty());

        assertThrows(WorkflowNotFoundException.class,
                () -> executionService.enqueueExecution("wf-100", "user-attacker"));

        verify(executionRepository, never()).save(any());
        verify(executionQueue, never()).enqueue(any());
    }

    @Test
    void enqueueExecution_ValidationFails_ThrowsWorkflowValidationException() {
        Workflow workflow = new Workflow("wf-invalid", "user-1", "Invalid WF", null, null, List.of(), List.of(), null, null);
        when(workflowRepository.findByIdAndUserId("wf-invalid", "user-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenThrow(new WorkflowValidationException("Cycle detected"));

        assertThrows(WorkflowValidationException.class,
                () -> executionService.enqueueExecution("wf-invalid", "user-1"));

        verify(executionRepository, never()).save(any());
        verify(executionQueue, never()).enqueue(any());
    }

    @Test
    void enqueueExecution_Success_CreatesQueuedRecordAndEnqueuesJob() {
        WorkflowNode node = new WorkflowNode("node-1", "trigger", Map.of("triggerType", "manual"));
        Workflow workflow = new Workflow("wf-1", "user-1", "Valid WF", null, null, List.of(node), List.of(), null, null);

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node));
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(inv -> {
            WorkflowExecution exec = inv.getArgument(0);
            exec.setId("exec-generated-1");
            return exec;
        });

        ExecuteWorkflowResponse response = executionService.enqueueExecution("wf-1", "user-1");

        assertNotNull(response);
        assertEquals("exec-generated-1", response.executionId());
        assertEquals("wf-1", response.workflowId());
        assertEquals(ExecutionStatus.QUEUED, response.status());

        // Verify MongoDB saved with QUEUED status
        ArgumentCaptor<WorkflowExecution> execCaptor = ArgumentCaptor.forClass(WorkflowExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        WorkflowExecution savedExec = execCaptor.getValue();
        assertEquals(ExecutionStatus.QUEUED, savedExec.getStatus());
        assertNotNull(savedExec.getQueuedAt());
        assertNull(savedExec.getStartedAt());
        assertNull(savedExec.getCompletedAt());

        // Verify Redis job enqueued
        ArgumentCaptor<ExecutionJob> jobCaptor = ArgumentCaptor.forClass(ExecutionJob.class);
        verify(executionQueue).enqueue(jobCaptor.capture());
        ExecutionJob enqueuedJob = jobCaptor.getValue();
        assertEquals("exec-generated-1", enqueuedJob.executionId());
        assertEquals("wf-1", enqueuedJob.workflowId());
        assertEquals("user-1", enqueuedJob.userId());
        assertEquals("manual", enqueuedJob.triggerType());
    }

    @Test
    void enqueueExecution_QueueThrowsException_MarksExecutionFailedInMongoAndThrowsQueueSubmissionException() {
        WorkflowNode node = new WorkflowNode("node-1", "trigger", Map.of());
        Workflow workflow = new Workflow("wf-1", "user-1", "Valid WF", null, null, List.of(node), List.of(), null, null);

        when(workflowRepository.findByIdAndUserId("wf-1", "user-1")).thenReturn(Optional.of(workflow));
        when(validator.validateAndOrder(workflow)).thenReturn(List.of(node));

        WorkflowExecution savedExec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        savedExec.setId("exec-fail-queue");
        when(executionRepository.save(any(WorkflowExecution.class))).thenReturn(savedExec);

        doThrow(new QueueException("Redis down")).when(executionQueue).enqueue(any());

        assertThrows(QueueSubmissionException.class,
                () -> executionService.enqueueExecution("wf-1", "user-1"));

        // Verify saved twice: first as QUEUED, second as FAILED after queue error
        verify(executionRepository, times(2)).save(any(WorkflowExecution.class));
        assertEquals(ExecutionStatus.FAILED, savedExec.getStatus());
        assertNotNull(savedExec.getCompletedAt());
        assertEquals(0L, savedExec.getDurationMs());
        assertTrue(savedExec.getError().contains("Failed to queue workflow execution"));
    }

    @Test
    void stateTransitions_ValidLifecycle_QueuedToRunningToSuccess() {
        WorkflowExecution exec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        assertEquals(ExecutionStatus.QUEUED, exec.getStatus());
        assertNull(exec.getStartedAt());
        assertNull(exec.getCompletedAt());

        Instant start = Instant.now();
        exec.markRunning(start);
        assertEquals(ExecutionStatus.RUNNING, exec.getStatus());
        assertEquals(start, exec.getStartedAt());

        Instant complete = start.plusMillis(200);
        exec.markSuccess(complete, List.of());
        assertEquals(ExecutionStatus.SUCCESS, exec.getStatus());
        assertEquals(complete, exec.getCompletedAt());
        assertEquals(200L, exec.getDurationMs());
        assertNull(exec.getError());
    }

    @Test
    void stateTransitions_InvalidTransition_ThrowsIllegalStateException() {
        WorkflowExecution exec = WorkflowExecution.queued("wf-1", "user-1", "manual");
        exec.markRunning(Instant.now());
        exec.markSuccess(Instant.now(), List.of());

        // Attempting to transition from SUCCESS to RUNNING must fail
        assertThrows(IllegalStateException.class, () -> exec.markRunning(Instant.now()));
    }
}
