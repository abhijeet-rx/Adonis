package com.adonis.repository;

import com.adonis.execution.ExecutionStatus;
import com.adonis.model.NodeExecution;
import com.adonis.model.WorkflowExecution;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
class WorkflowExecutionRepositoryTest {

    private static MongoServer server;
    private static InetSocketAddress serverAddress;

    @BeforeAll
    static void setUpServer() {
        server = new MongoServer(new MemoryBackend());
        serverAddress = server.bind();
    }

    @AfterAll
    static void tearDownServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @DynamicPropertySource
    static void setMongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                () -> "mongodb://" + serverAddress.getHostName() + ":" + serverAddress.getPort() + "/adonis-exec-test");
    }

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        executionRepository.deleteAll();
    }

    @Test
    void saveAndFindByIdAndUserId_OwnershipRespected() {
        WorkflowExecution exec = new WorkflowExecution();
        exec.setWorkflowId("wf-1");
        exec.setUserId("user-A");
        exec.setStatus(ExecutionStatus.SUCCESS);
        exec.setTriggerType("manual");
        exec.setStartedAt(Instant.now());
        exec.setCompletedAt(Instant.now());
        exec.setDurationMs(120L);
        exec.setNodeExecutions(List.of(
                NodeExecution.success("node-1", "trigger", Instant.now(), Instant.now(), Map.of(), Map.of("key", "val"))
        ));

        WorkflowExecution saved = executionRepository.save(exec);
        assertNotNull(saved.getId());

        // Find by owner
        Optional<WorkflowExecution> found = executionRepository.findByIdAndUserId(saved.getId(), "user-A");
        assertTrue(found.isPresent());
        assertEquals("wf-1", found.get().getWorkflowId());
        assertEquals(1, found.get().getNodeExecutions().size());

        // Cannot find by another user
        Optional<WorkflowExecution> notFound = executionRepository.findByIdAndUserId(saved.getId(), "user-B");
        assertTrue(notFound.isEmpty());
    }

    @Test
    void findByWorkflowIdAndUserId_PaginationAndSortingNewestFirst() {
        Instant t1 = Instant.parse("2026-09-29T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-29T10:05:00Z");
        Instant t3 = Instant.parse("2026-09-29T10:10:00Z");

        WorkflowExecution e1 = new WorkflowExecution(null, "wf-page", "user-1", ExecutionStatus.SUCCESS, "manual", t1, t1.plusSeconds(1), 1000L, List.of(), null);
        WorkflowExecution e2 = new WorkflowExecution(null, "wf-page", "user-1", ExecutionStatus.FAILED, "manual", t2, t2.plusSeconds(1), 1000L, List.of(), "error");
        WorkflowExecution e3 = new WorkflowExecution(null, "wf-page", "user-1", ExecutionStatus.SUCCESS, "manual", t3, t3.plusSeconds(2), 2000L, List.of(), null);
        // Another workflow / user
        WorkflowExecution eOther = new WorkflowExecution(null, "wf-other", "user-2", ExecutionStatus.SUCCESS, "manual", t3, t3.plusSeconds(1), 1000L, List.of(), null);

        executionRepository.saveAll(List.of(e1, e2, e3, eOther));

        // Query page 0, size 2, sorted by startedAt DESC
        PageRequest pageRequest = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<WorkflowExecution> page = executionRepository.findByWorkflowIdAndUserId("wf-page", "user-1", pageRequest);

        assertEquals(3, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        assertEquals(2, page.getContent().size());

        // Newest first: e3 (10:10:00), then e2 (10:05:00)
        assertEquals(t3, page.getContent().get(0).getStartedAt());
        assertEquals(t2, page.getContent().get(1).getStartedAt());

        // Query page 1
        PageRequest page1Request = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<WorkflowExecution> page1 = executionRepository.findByWorkflowIdAndUserId("wf-page", "user-1", page1Request);
        assertEquals(1, page1.getContent().size());
        assertEquals(t1, page1.getContent().get(0).getStartedAt());
    }

    @Test
    void findAllByUserId_OnlyReturnsUserRecords() {
        WorkflowExecution eUser1 = new WorkflowExecution(null, "wf-1", "user-1", ExecutionStatus.SUCCESS, "manual", Instant.now(), Instant.now(), 500L, List.of(), null);
        WorkflowExecution eUser2 = new WorkflowExecution(null, "wf-2", "user-2", ExecutionStatus.SUCCESS, "manual", Instant.now(), Instant.now(), 500L, List.of(), null);

        executionRepository.saveAll(List.of(eUser1, eUser2));

        Page<WorkflowExecution> user1Page = executionRepository.findAllByUserId("user-1", PageRequest.of(0, 10));
        assertEquals(1, user1Page.getTotalElements());
        assertEquals("user-1", user1Page.getContent().get(0).getUserId());

        Page<WorkflowExecution> user2Page = executionRepository.findAllByUserId("user-2", PageRequest.of(0, 10));
        assertEquals(1, user2Page.getTotalElements());
        assertEquals("user-2", user2Page.getContent().get(0).getUserId());
    }
}
