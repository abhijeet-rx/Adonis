package com.adonis.integration.persistence;

import com.adonis.execution.ExecutionStatus;
import com.adonis.integration.support.AdonisIntegrationTest;
import com.adonis.integration.support.TestDataFactory;
import com.adonis.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MongoDB Persistence Integration Tests (Real MongoDB Testcontainer)")
class MongoPersistenceIntegrationTest extends AdonisIntegrationTest {

    @Test
    @DisplayName("User persistence: saves user, normalizes email, and retrieves by ID and email")
    void userPersistence_CreateAndRetrieve_SucceedsWithNormalizedEmail() {
        User user = TestDataFactory.createUser("Alice Walker", "Alice.Walker@EXAMPLE.com", "hash_secret_123");
        User saved = userRepository.save(user);

        assertNotNull(saved.getId());
        assertEquals("alice.walker@example.com", saved.getEmail(), "Email must be normalized to lowercase");
        assertEquals("Alice Walker", saved.getName());
        assertEquals("hash_secret_123", saved.getPasswordHash());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        Optional<User> byId = userRepository.findById(saved.getId());
        assertTrue(byId.isPresent());
        assertEquals("alice.walker@example.com", byId.get().getEmail());

        Optional<User> byEmail = userRepository.findByEmail("alice.walker@example.com");
        assertTrue(byEmail.isPresent());
        assertEquals(saved.getId(), byEmail.get().getId());
    }

    @Test
    @DisplayName("User persistence: enforces unique email index constraint")
    void userPersistence_UniqueEmailConstraint_RejectsDuplicates() {
        User first = TestDataFactory.createUser("User One", "duplicate@example.com", "hash1");
        userRepository.save(first);

        User second = TestDataFactory.createUser("User Two", "DUPLICATE@example.com", "hash2");

        assertThrows(DuplicateKeyException.class, () -> userRepository.save(second),
                "Saving user with duplicate email must trigger MongoDB DuplicateKeyException");
    }

    @Test
    @DisplayName("Workflow persistence: supports full CRUD with user ownership filtering")
    void workflowPersistence_CrudOperations_SucceedsWithOwnershipFilter() {
        String userA = "user-A";
        String userB = "user-B";

        Workflow wfA = TestDataFactory.createHttpWorkflow(userA, "Workflow A", "https://api.example.com", "GET");
        Workflow savedA = workflowRepository.save(wfA);
        assertNotNull(savedA.getId());

        Workflow wfB = TestDataFactory.createHttpWorkflow(userB, "Workflow B", "https://api.example.com", "POST");
        Workflow savedB = workflowRepository.save(wfB);
        assertNotNull(savedB.getId());

        // Ownership filtering
        List<Workflow> userAWorkflows = workflowRepository.findByUserId(userA);
        assertEquals(1, userAWorkflows.size());
        assertEquals(savedA.getId(), userAWorkflows.get(0).getId());

        Optional<Workflow> foundForOwner = workflowRepository.findByIdAndUserId(savedA.getId(), userA);
        assertTrue(foundForOwner.isPresent());

        Optional<Workflow> foundForNonOwner = workflowRepository.findByIdAndUserId(savedA.getId(), userB);
        assertTrue(foundForNonOwner.isEmpty(), "Non-owner must not see other user's workflow");

        // Update
        savedA.setName("Workflow A Updated");
        savedA.setStatus(WorkflowStatus.ACTIVE);
        Workflow updated = workflowRepository.save(savedA);
        assertEquals("Workflow A Updated", updated.getName());
        assertEquals(WorkflowStatus.ACTIVE, updated.getStatus());

        // Delete
        workflowRepository.deleteById(savedA.getId());
        assertTrue(workflowRepository.findById(savedA.getId()).isEmpty());
        assertTrue(workflowRepository.findById(savedB.getId()).isPresent(), "User B workflow must remain intact");
    }

    @Test
    @DisplayName("Execution persistence: supports status transitions, query history, and pagination")
    void executionPersistence_CreateUpdateAndQueryHistoryWithPagination() {
        String workflowId = "wf-exec-test";
        String userId = "user-exec-1";

        // Create 3 executions with different timestamps
        Instant t1 = Instant.now().minusSeconds(30);
        Instant t2 = Instant.now().minusSeconds(20);
        Instant t3 = Instant.now().minusSeconds(10);

        WorkflowExecution exec1 = new WorkflowExecution("e-1", workflowId, userId, ExecutionStatus.SUCCESS, "MANUAL", t1, t1.plusMillis(500), 500L, List.of(), null);
        WorkflowExecution exec2 = new WorkflowExecution("e-2", workflowId, userId, ExecutionStatus.FAILED, "MANUAL", t2, t2.plusMillis(300), 300L, List.of(), "Simulated error");
        WorkflowExecution exec3 = new WorkflowExecution("e-3", workflowId, userId, ExecutionStatus.RUNNING, "MANUAL", t3, null, null, List.of(), null);

        executionRepository.saveAll(List.of(exec1, exec2, exec3));

        // Query by workflowId and userId with pagination (page 0, size 2, sorted by startedAt desc)
        PageRequest pageRequest = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<WorkflowExecution> page = executionRepository.findByWorkflowIdAndUserId(workflowId, userId, pageRequest);

        assertEquals(3, page.getTotalElements());
        assertEquals(2, page.getContent().size());
        assertEquals("e-3", page.getContent().get(0).getId());
        assertEquals("e-2", page.getContent().get(1).getId());

        // Update running execution to terminal status
        WorkflowExecution running = executionRepository.findById("e-3").orElseThrow();
        running.markSuccess(Instant.now(), List.of());
        executionRepository.save(running);

        WorkflowExecution completed = executionRepository.findById("e-3").orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, completed.getStatus());
        assertNotNull(completed.getCompletedAt());
        assertNotNull(completed.getDurationMs());
    }

    @Test
    @DisplayName("Execution persistence: verifies NodeExecution and retry attempts history persistence")
    void executionPersistence_NodeExecutionAndRetryAttempts_PersistedDurably() {
        String workflowId = "wf-retry-persist";
        String userId = "user-1";

        Instant now = Instant.now();

        NodeExecutionAttempt attempt1 = new NodeExecutionAttempt(
                1,
                ExecutionStatus.FAILED,
                now.minusSeconds(2),
                now.minusSeconds(1),
                1000L,
                Map.of("prompt", "Analyze data"),
                Map.of(),
                "HTTP 429 Rate Limit"
        );

        NodeExecutionAttempt attempt2 = new NodeExecutionAttempt(
                2,
                ExecutionStatus.SUCCESS,
                now.minusSeconds(1),
                now,
                800L,
                Map.of("prompt", "Analyze data"),
                Map.of("analysis", "complete"),
                null
        );

        NodeExecution nodeExec = new NodeExecution(
                "ai_node_1",
                "ai_text_generation",
                ExecutionStatus.SUCCESS,
                now.minusSeconds(2),
                now,
                1800L,
                Map.of("prompt", "Analyze data"),
                Map.of("analysis", "complete"),
                null,
                1,
                List.of(attempt1, attempt2)
        );

        WorkflowExecution execution = new WorkflowExecution(
                "e-retry-test",
                workflowId,
                userId,
                ExecutionStatus.SUCCESS,
                "MANUAL",
                now.minusSeconds(2),
                now,
                1800L,
                List.of(nodeExec),
                null
        );

        executionRepository.save(execution);

        WorkflowExecution retrieved = executionRepository.findById("e-retry-test").orElseThrow();
        assertEquals(1, retrieved.getNodeExecutions().size());

        NodeExecution retrievedNode = retrieved.getNodeExecutions().get(0);
        assertEquals("ai_node_1", retrievedNode.getNodeId());
        assertEquals(1, retrievedNode.getRetryCount());
        assertNotNull(retrievedNode.getAttempts());
        assertEquals(2, retrievedNode.getAttempts().size());

        NodeExecutionAttempt a1 = retrievedNode.getAttempts().get(0);
        assertEquals(1, a1.getAttemptNumber());
        assertEquals(ExecutionStatus.FAILED, a1.getStatus());
        assertEquals("HTTP 429 Rate Limit", a1.getError());
        assertEquals("Analyze data", a1.getInput().get("prompt"));

        NodeExecutionAttempt a2 = retrievedNode.getAttempts().get(1);
        assertEquals(2, a2.getAttemptNumber());
        assertEquals(ExecutionStatus.SUCCESS, a2.getStatus());
        assertNull(a2.getError());
        assertEquals("complete", a2.getOutput().get("analysis"));
    }

    @Test
    @DisplayName("Scheduled occurrence persistence: enforces unique occurrenceKey constraint")
    void scheduledOccurrence_UniqueOccurrenceKeyConstraint_RejectsDuplicates() {
        String workflowId = "wf-sched-unique";
        Instant fireTime = Instant.parse("2026-10-01T12:00:00Z");
        String occurrenceKey = ScheduledOccurrence.buildOccurrenceKey(workflowId, fireTime);

        ScheduledOccurrence occurrence1 = new ScheduledOccurrence(
                occurrenceKey,
                workflowId,
                fireTime,
                "exec-1",
                ScheduledOccurrenceStatus.ENQUEUED,
                Instant.now()
        );

        scheduledOccurrenceRepository.save(occurrence1);

        ScheduledOccurrence occurrence2 = new ScheduledOccurrence(
                occurrenceKey,
                workflowId,
                fireTime,
                "exec-2",
                ScheduledOccurrenceStatus.CLAIMED,
                Instant.now()
        );

        assertThrows(DuplicateKeyException.class, () -> mongoTemplate.insert(occurrence2),
                "Duplicate occurrenceKey must be rejected by unique compound index on scheduled_occurrences");
    }
}
