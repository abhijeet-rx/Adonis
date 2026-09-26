package com.adonis.repository;

import com.adonis.model.Workflow;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowNodePosition;
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
        Workflow found = retrieved.get();
        assertEquals(saved.getId(), found.getId());
        assertEquals("user-1", found.getUserId());
        assertEquals("Order Processing", found.getName());
        assertEquals("Handles customer orders", found.getDescription());
        assertEquals(WorkflowStatus.ACTIVE, found.getStatus());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());

        assertEquals(1, found.getNodes().size());
        assertEquals("node-1", found.getNodes().get(0).getId());
        assertEquals("http-request", found.getNodes().get(0).getType());
        assertEquals("https://api.example.com", found.getNodes().get(0).getData().get("url"));

        assertEquals(1, found.getEdges().size());
        assertEquals("edge-1", found.getEdges().get(0).getId());
        assertEquals("node-1", found.getEdges().get(0).getSource());
        assertEquals("node-2", found.getEdges().get(0).getTarget());
    }

    @Test
    void shouldPersistAndRetrieveNodesWithPositionAndEdgesWithHandles() {
        WorkflowNode node = new WorkflowNode(
                "node-visual-1",
                "httpRequest",
                Map.of("label", "Fetch Data", "url", "https://api.example.com", "method", "GET"),
                new WorkflowNodePosition(250.5, 350.0)
        );
        WorkflowEdge edge = new WorkflowEdge("edge-visual-1", "node-visual-1", "node-visual-2", "source-handle-1", "target-handle-1");

        Workflow workflow = Workflow.create("user-canvas", "Visual Pipeline", "Visual description",
                WorkflowStatus.DRAFT, List.of(node), List.of(edge));
        Workflow saved = workflowRepository.save(workflow);

        Optional<Workflow> retrieved = workflowRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        Workflow found = retrieved.get();

        assertEquals(1, found.getNodes().size());
        WorkflowNode foundNode = found.getNodes().get(0);
        assertEquals("node-visual-1", foundNode.getId());
        assertNotNull(foundNode.getPosition());
        assertEquals(250.5, foundNode.getPosition().getX());
        assertEquals(350.0, foundNode.getPosition().getY());

        assertEquals(1, found.getEdges().size());
        WorkflowEdge foundEdge = found.getEdges().get(0);
        assertEquals("edge-visual-1", foundEdge.getId());
        assertEquals("source-handle-1", foundEdge.getSourceHandle());
        assertEquals("target-handle-1", foundEdge.getTargetHandle());
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
        long nonOwnerDeleted = workflowRepository.deleteByIdAndUserId(wf.getId(), "user-B");
        assertEquals(0L, nonOwnerDeleted);

        // Workflow still exists
        assertTrue(workflowRepository.findById(wf.getId()).isPresent());

        // Delete with correct owner ID
        long ownerDeleted = workflowRepository.deleteByIdAndUserId(wf.getId(), "user-A");
        assertEquals(1L, ownerDeleted);

        // Workflow is now deleted
        assertTrue(workflowRepository.findById(wf.getId()).isEmpty());
    }
}
