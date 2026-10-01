package com.adonis.integration.support;

import com.adonis.AdonisApplication;
import com.adonis.execution.WorkflowExecutionEngine;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.model.ScheduledOccurrence;
import com.adonis.model.User;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.queue.ExecutionQueue;
import com.adonis.queue.ExecutionWorker;
import com.adonis.queue.RedisExecutionQueue;
import com.adonis.repository.ScheduledOccurrenceRepository;
import com.adonis.repository.UserRepository;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.scheduler.AdonisScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base abstract class for all Phase 10 integration tests.
 * Manages real MongoDB and Redis Testcontainers, dynamic Spring Boot property injection,
 * local mock HTTP server lifecycle, test data isolation, and common dependencies.
 */
@SpringBootTest(classes = AdonisApplication.class)
@Testcontainers
public abstract class AdonisIntegrationTest {

    protected static final LocalMockHttpServer mockHttpServer = new LocalMockHttpServer();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(mockHttpServer::stop));
    }

    public static final String TEST_STREAM_KEY = "adonis:integration:stream";
    public static final String TEST_CONSUMER_GROUP = "adonis-integration-workers";

    @BeforeAll
    public static void setupSharedInfrastructure() {
        mockHttpServer.start();

        boolean inCi = DockerAvailability.isCi();
        boolean dockerAvailable = DockerAvailability.isDockerAvailable();

        if (inCi) {
            if (!dockerAvailable) {
                throw new IllegalStateException("Docker must be available in CI environment to run Testcontainers integration tests");
            }
        } else {
            assumeTrue(dockerAvailable, "Docker is not available on this host. Skipping Testcontainers integration test locally.");
        }
    }

    @AfterAll
    public static void teardownSharedInfrastructure() {
        // Keep mockHttpServer running across test classes sharing the Spring context.
        // It is stopped automatically on JVM shutdown hook.
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        mockHttpServer.start();

        if (DockerAvailability.isDockerAvailable()) {
            // Real MongoDB Testcontainer
            registry.add("spring.data.mongodb.uri", () -> MongoTestContainer.getReplicaSetUrl("adonis-integration-test"));
            registry.add("spring.data.mongodb.auto-index-creation", () -> "true");

            // Real Redis Testcontainer
            registry.add("spring.data.redis.host", RedisTestContainer::getHost);
            registry.add("spring.data.redis.port", RedisTestContainer::getPort);
            registry.add("adonis.queue.type", () -> "redis");
        } else {
            if (DockerAvailability.isCi()) {
                throw new IllegalStateException("Docker is required in CI for Testcontainers integration testing");
            }
            // Fallback placeholder properties for non-Docker environments during local compilation
            registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/adonis-test");
            registry.add("spring.data.mongodb.auto-index-creation", () -> "false");
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> 6379);
            registry.add("adonis.queue.type", () -> "in-memory");
        }

        // Shared security & queue configuration
        registry.add("adonis.jwt.secret", () -> "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        registry.add("adonis.jwt.expiration-ms", () -> 86400000);
        registry.add("adonis.worker.enabled", () -> "false");
        registry.add("adonis.scheduler.enabled", () -> "true");
        registry.add("adonis.scheduler.polling-interval-ms", () -> 86400000);
        registry.add("adonis.worker.stream-name", () -> TEST_STREAM_KEY);
        registry.add("adonis.worker.consumer-group", () -> TEST_CONSUMER_GROUP);
        registry.add("adonis.worker.poll-timeout-ms", () -> 500);
        registry.add("adonis.worker.lease-duration-ms", () -> 10000);
        registry.add("adonis.worker.heartbeat-interval-ms", () -> 2000);

        // AI Provider test endpoints pointing to local mock HTTP server
        registry.add("adonis.ai.openai.api-key", () -> "sk-test-mock-openai-key-123456789012345678901234");
        registry.add("adonis.ai.openai.base-url", mockHttpServer::getOpenAiBaseUrl);
        registry.add("adonis.ai.gemini.api-key", () -> "AIzaSy-test-mock-gemini-key-12345678901234567890123456");
        registry.add("adonis.ai.gemini.base-url", mockHttpServer::getGeminiBaseUrl);
        registry.add("adonis.ai.timeout.connect-ms", () -> 2000);
        registry.add("adonis.ai.timeout.read-ms", () -> 4000);
    }

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Autowired
    protected MongoMappingContext mongoMappingContext;

    @Autowired(required = false)
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected WorkflowRepository workflowRepository;

    @Autowired
    protected WorkflowExecutionRepository executionRepository;

    @Autowired
    protected ScheduledOccurrenceRepository scheduledOccurrenceRepository;

    @Autowired
    protected WorkflowExecutionEngine engine;

    @Autowired
    protected WorkflowExecutionService executionService;

    @Autowired
    protected ExecutionQueue queue;

    @Autowired
    protected ExecutionWorker worker;

    @Autowired(required = false)
    protected AdonisScheduler scheduler;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    public void cleanupAndPrepareIsolation() {
        if (!DockerAvailability.isDockerAvailable()) {
            return;
        }

        mockHttpServer.reset();

        // 1. Clean MongoDB test collections
        userRepository.deleteAll();
        workflowRepository.deleteAll();
        executionRepository.deleteAll();
        scheduledOccurrenceRepository.deleteAll();

        // 2. Ensure indexes exist for persistence tests
        ensureIndexes(User.class);
        ensureIndexes(Workflow.class);
        ensureIndexes(WorkflowExecution.class);
        ensureIndexes(ScheduledOccurrence.class);

        // 3. Clean Redis test stream
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(TEST_STREAM_KEY);
                redisTemplate.delete(TEST_STREAM_KEY + ":dlq");
                if (queue instanceof RedisExecutionQueue redisQueue) {
                    redisQueue.resetGroupInitialization();
                    redisQueue.ensureGroupExists();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private <T> void ensureIndexes(Class<T> entityClass) {
        try {
            IndexOperations indexOps = mongoTemplate.indexOps(entityClass);
            IndexResolver resolver = new MongoPersistentEntityIndexResolver(mongoMappingContext);
            resolver.resolveIndexFor(entityClass).forEach(indexOps::ensureIndex);
        } catch (Exception ignored) {
        }
    }
}
