package com.adonis.repository;

import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowStatus;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
class WorkflowRepositoryTest {

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
                () -> "mongodb://" + serverAddress.getHostName() + ":" + serverAddress.getPort() + "/adonis-workflow-test");
    }

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        workflowRepository.deleteAll();
    }

    @Test
    void shouldSaveAndFindWorkflowById() {
        WorkflowNode node = new WorkflowNode("node-1", "http-request", Map.of("url", "https://api.example.com"));
        WorkflowEdge edge = new WorkflowEdge("edge-1", "node-1", "node-2");

        Workflow workflow = Workflow.create("user-1", "Order Processing", "Handles customer orders",
                WorkflowStatus.ACTIVE, List.of(node), List.of(edge));
        Workflow saved = workflowRepository.save(workflow);

        assertNotNull(saved.getId());
        assertEquals("user-1", saved.getUserId());
        assertEquals("Order Processing", saved.getName());
        assertEquals(WorkflowStatus.ACTIVE, saved.getStatus());
        assertEquals(1, saved.getNodes().size());
        assertEquals("node-1", saved.getNodes().get(0).getId());
        assertEquals(1, saved.getEdges().size());
        assertEquals("edge-1", saved.getEdges().get(0).getId());

        Optional<Workflow> retrieved = workflowRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals("Order Processing", retrieved.get().getName());
    }

    @Test
    void shouldFindByUserIdReturningOnlyWorkflowsOwnedByUser() {
        Workflow wf1 = Workflow.create("user-A", "User A Workflow 1", "Desc", WorkflowStatus.DRAFT, List.of(), List.of());
        Workflow wf2 = Workflow.create("user-A", "User A Workflow 2", "Desc", WorkflowStatus.ACTIVE, List.of(), List.of());
        Workflow wf3 = Workflow.create("user-B", "User B Workflow 1", "Desc", WorkflowStatus.DRAFT, List.of(), List.of());

        workflowRepository.saveAll(List.of(wf1, wf2, wf3));

        List<Workflow> userAWorkflows = workflowRepository.findByUserId("user-A");
        assertEquals(2, userAWorkflows.size());
        assertTrue(userAWorkflows.stream().allMatch(w -> "user-A".equals(w.getUserId())));

        List<Workflow> userBWorkflows = workflowRepository.findByUserId("user-B");
        assertEquals(1, userBWorkflows.size());
        assertEquals("User B Workflow 1", userBWorkflows.get(0).getName());
    }

    @Test
    void shouldFindByIdAndUserIdEnforcingOwnershipQueryIsolation() {
        Workflow userAWorkflow = workflowRepository.save(
                Workflow.create("user-A", "Secret Workflow", "Confidential", WorkflowStatus.ACTIVE, List.of(), List.of())
        );

        // User A query finds it
        Optional<Workflow> foundByOwner = workflowRepository.findByIdAndUserId(userAWorkflow.getId(), "user-A");
        assertTrue(foundByOwner.isPresent());
        assertEquals(userAWorkflow.getId(), foundByOwner.get().getId());

        // User B query for User A's workflow ID returns empty (strictly isolated at DB query level)
        Optional<Workflow> foundByNonOwner = workflowRepository.findByIdAndUserId(userAWorkflow.getId(), "user-B");
        assertTrue(foundByNonOwner.isEmpty());
    }

    @Test
    void shouldDeleteByIdAndUserIdOnlyWhenUserMatches() {
        Workflow wf = workflowRepository.save(
                Workflow.create("user-A", "Deletable Workflow", "Desc", WorkflowStatus.DRAFT, List.of(), List.of())
        );

        // Attempt delete with wrong user ID
        workflowRepository.deleteByIdAndUserId(wf.getId(), "user-B");

        // Workflow still exists
        assertTrue(workflowRepository.findById(wf.getId()).isPresent());

        // Delete with correct owner ID
        workflowRepository.deleteByIdAndUserId(wf.getId(), "user-A");

        // Workflow is now deleted
        assertTrue(workflowRepository.findById(wf.getId()).isEmpty());
    }
}
