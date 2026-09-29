package com.adonis.execution;

import com.adonis.model.NodeExecutionAttempt;
import com.adonis.model.RetryConfig;
import com.adonis.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    private final FailureClassifier failureClassifier = new FailureClassifier();

    static class RecordingDelayStrategy implements RetryDelayStrategy {
        final List<Long> delays = new ArrayList<>();

        @Override
        public void delay(long millis) {
            delays.add(millis);
        }
    }

    static class SimpleNodeExecutor implements NodeExecutor {
        private final Function<WorkflowNode, NodeExecutionResult> function;

        SimpleNodeExecutor(Function<WorkflowNode, NodeExecutionResult> function) {
            this.function = function;
        }

        @Override
        public boolean supports(String nodeType) {
            return true;
        }

        @Override
        public NodeExecutionResult execute(WorkflowNode node, Map<String, Object> input, ExecutionContext context) {
            return function.apply(node);
        }
    }

    @Test
    void calculateBackoff_CalculatesExponentialBackoffCorrectly() {
        RetryPolicy policy = new RetryPolicy(failureClassifier, RetryDelayStrategy.noOp());
        RetryConfig config = new RetryConfig(true, 5, 1000L, 2.0, 30000L);

        // retry 1 -> 1000ms
        assertEquals(1000L, policy.calculateBackoff(config, 1));
        // retry 2 -> 2000ms
        assertEquals(2000L, policy.calculateBackoff(config, 2));
        // retry 3 -> 4000ms
        assertEquals(4000L, policy.calculateBackoff(config, 3));
        // retry 4 -> 8000ms
        assertEquals(8000L, policy.calculateBackoff(config, 4));
        // retry 5 -> 16000ms
        assertEquals(16000L, policy.calculateBackoff(config, 5));
    }

    @Test
    void calculateBackoff_RespectsMaxBackoffLimit() {
        RetryPolicy policy = new RetryPolicy(failureClassifier, RetryDelayStrategy.noOp());
        RetryConfig config = new RetryConfig(true, 5, 1000L, 2.0, 5000L);

        assertEquals(1000L, policy.calculateBackoff(config, 1));
        assertEquals(2000L, policy.calculateBackoff(config, 2));
        assertEquals(4000L, policy.calculateBackoff(config, 3));
        assertEquals(5000L, policy.calculateBackoff(config, 4)); // Clamped to 5000L
        assertEquals(5000L, policy.calculateBackoff(config, 5)); // Clamped to 5000L
    }

    @Test
    void calculateBackoff_ProtectsAgainstOverflow() {
        RetryPolicy policy = new RetryPolicy(failureClassifier, RetryDelayStrategy.noOp());
        RetryConfig config = new RetryConfig(true, 10, 50000L, 10.0, 60000L);

        long backoff = policy.calculateBackoff(config, 100);
        assertEquals(60000L, backoff);
    }

    @Test
    void executeWithRetry_MaxRetries0_ExecutesOnlyOnce() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-1", "custom", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 0)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor failingExecutor = new SimpleNodeExecutor(n -> {
            callCount.incrementAndGet();
            return NodeExecutionResult.failure(n.getId(), n.getType(), Instant.now(), Instant.now(), "HTTP 503 SERVICE_UNAVAILABLE");
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, failingExecutor);

        assertEquals(1, callCount.get());
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(0, result.retryCount());
        assertEquals(1, result.attempts().size());
        assertTrue(delayStrategy.delays.isEmpty());
    }

    @Test
    void executeWithRetry_MaxRetries1_ExecutesTwiceOnFailure() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-1", "custom", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 1, "initialBackoffMs", 1000L)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor failingExecutor = new SimpleNodeExecutor(n -> {
            callCount.incrementAndGet();
            return NodeExecutionResult.failure(n.getId(), n.getType(), Instant.now(), Instant.now(), "HTTP 503 SERVICE_UNAVAILABLE");
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, failingExecutor);

        assertEquals(2, callCount.get());
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(1, result.retryCount());
        assertEquals(2, result.attempts().size());
        assertEquals(List.of(1000L), delayStrategy.delays);
        assertTrue(result.error().contains("Node failed after 2 attempts"));
    }

    @Test
    void executeWithRetry_MaxRetries3_ExecutesFourTimesOnFailure() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-1", "custom", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 3, "initialBackoffMs", 1000L, "backoffMultiplier", 2.0)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor failingExecutor = new SimpleNodeExecutor(n -> {
            callCount.incrementAndGet();
            return NodeExecutionResult.failure(n.getId(), n.getType(), Instant.now(), Instant.now(), "HTTP 503 SERVICE_UNAVAILABLE");
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, failingExecutor);

        assertEquals(4, callCount.get(), "maxRetries = 3 means 1 initial attempt + 3 retries = 4 total attempts");
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(3, result.retryCount());
        assertEquals(4, result.attempts().size());
        assertEquals(List.of(1000L, 2000L, 4000L), delayStrategy.delays);
    }

    @Test
    void executeWithRetry_SucceedsOnSecondAttempt_RecordsAttemptsAndReturnsSuccess() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-1", "custom", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 3, "initialBackoffMs", 500L)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor executor = new SimpleNodeExecutor(n -> {
            int attempt = callCount.incrementAndGet();
            if (attempt == 1) {
                return NodeExecutionResult.failure(n.getId(), n.getType(), Instant.now(), Instant.now(), "Connection timeout");
            }
            return NodeExecutionResult.success(n.getId(), n.getType(), Instant.now(), Instant.now(), Map.of("processed", true));
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, executor);

        assertEquals(2, callCount.get());
        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(1, result.retryCount());
        assertEquals(2, result.attempts().size());

        NodeExecutionAttempt attempt1 = result.attempts().get(0);
        assertEquals(1, attempt1.getAttemptNumber());
        assertEquals(ExecutionStatus.FAILED, attempt1.getStatus());
        assertTrue(attempt1.getError().contains("Connection timeout"));

        NodeExecutionAttempt attempt2 = result.attempts().get(1);
        assertEquals(2, attempt2.getAttemptNumber());
        assertEquals(ExecutionStatus.SUCCESS, attempt2.getStatus());
        assertEquals(true, attempt2.getOutput().get("processed"));
    }

    @Test
    void executeWithRetry_NonRetryableError_FailsImmediatelyWithoutRetrying() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-1", "custom", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 3)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor authFailingExecutor = new SimpleNodeExecutor(n -> {
            callCount.incrementAndGet();
            return NodeExecutionResult.failure(n.getId(), n.getType(), Instant.now(), Instant.now(),
                    Map.of(), Map.of("statusCode", 401), "HTTP 401 UNAUTHORIZED");
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, authFailingExecutor);

        assertEquals(1, callCount.get(), "Non-retryable 401 must not trigger retries");
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(0, result.retryCount());
        assertEquals(1, result.attempts().size());
        assertTrue(delayStrategy.delays.isEmpty());
    }

    @Test
    void executeWithRetry_RetryDisabled_ExecutesOnlyOnceEvenIfRetryable() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-disabled", "custom", Map.of(
                "retry", Map.of("enabled", false, "maxRetries", 3)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor failingExecutor = new SimpleNodeExecutor(n -> {
            callCount.incrementAndGet();
            return NodeExecutionResult.failure(n.getId(), n.getType(), Instant.now(), Instant.now(),
                    Map.of(), Map.of("statusCode", 503), "HTTP 503 SERVICE_UNAVAILABLE");
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, failingExecutor);

        assertEquals(1, callCount.get(), "Retry disabled must only attempt once");
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(0, result.retryCount());
        assertEquals(1, result.attempts().size());
        assertTrue(delayStrategy.delays.isEmpty(), "No backoff delay when retry is disabled");
    }

    @Test
    void executeWithRetry_NoRetryConfigInNodeData_ExecutesOnlyOnceEvenIfRetryable() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        // Node with zero retry configuration (backward compatibility)
        WorkflowNode node = new WorkflowNode("node-no-config", "custom", Map.of());
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor failingExecutor = new SimpleNodeExecutor(n -> {
            callCount.incrementAndGet();
            return NodeExecutionResult.failure(n.getId(), n.getType(), Instant.now(), Instant.now(),
                    Map.of(), Map.of("statusCode", 503), "HTTP 503 SERVICE_UNAVAILABLE");
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, failingExecutor);

        assertEquals(1, callCount.get(), "Workflows without retry configuration must default to 1 attempt");
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(0, result.retryCount());
        assertEquals(1, result.attempts().size());
        assertTrue(delayStrategy.delays.isEmpty());
    }

    @Test
    void executeWithRetry_SuccessfulRetryOnThirdAttempt() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-3rd-success", "custom", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 3, "initialBackoffMs", 1000L, "backoffMultiplier", 2.0)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor executor = new SimpleNodeExecutor(n -> {
            int attempt = callCount.incrementAndGet();
            Instant now = Instant.now();
            if (attempt == 1) {
                return NodeExecutionResult.failure(n.getId(), n.getType(), now, now,
                        Map.of(), Map.of("statusCode", 503), "HTTP 503 Attempt 1");
            }
            if (attempt == 2) {
                return NodeExecutionResult.failure(n.getId(), n.getType(), now, now,
                        Map.of(), Map.of("statusCode", 503), "HTTP 503 Attempt 2");
            }
            return NodeExecutionResult.success(n.getId(), n.getType(), now, now,
                    Map.of(), Map.of("statusCode", 200, "data", "successOnAttempt3"));
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, executor);

        assertEquals(3, callCount.get());
        assertEquals(ExecutionStatus.SUCCESS, result.status());
        assertEquals(2, result.retryCount(), "Two retries occurred before success");
        assertEquals(3, result.attempts().size(), "Three attempts recorded");
        assertEquals(2, delayStrategy.delays.size(), "Two backoff delays executed");
        assertEquals(1000L, delayStrategy.delays.get(0));
        assertEquals(2000L, delayStrategy.delays.get(1));
        assertEquals("successOnAttempt3", result.output().get("data"));
    }

    @Test
    void executeWithRetry_ExhaustedRetriesWithMaxRetries2() {
        RecordingDelayStrategy delayStrategy = new RecordingDelayStrategy();
        RetryPolicy policy = new RetryPolicy(failureClassifier, delayStrategy);

        WorkflowNode node = new WorkflowNode("node-exhausted-2", "custom", Map.of(
                "retry", Map.of("enabled", true, "maxRetries", 2, "initialBackoffMs", 1000L)
        ));
        ExecutionContext context = new ExecutionContext("e-1", "w-1", "u-1", Instant.now());

        AtomicInteger callCount = new AtomicInteger();
        NodeExecutor executor = new SimpleNodeExecutor(n -> {
            callCount.incrementAndGet();
            Instant now = Instant.now();
            return NodeExecutionResult.failure(n.getId(), n.getType(), now, now,
                    Map.of(), Map.of("statusCode", 503), "HTTP 503 Persistent Fault");
        });

        NodeExecutionResult result = policy.executeWithRetry(node, Map.of(), context, executor);

        assertEquals(3, callCount.get(), "maxRetries = 2 means 1 initial + 2 retries = 3 attempts");
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(2, result.retryCount());
        assertEquals(3, result.attempts().size());
        assertEquals(List.of(1000L, 2000L), delayStrategy.delays);
        assertTrue(result.error().contains("Node failed after 3 attempts"));
    }

    @Test
    void executeWithRetry_ThreadSafetyAndExecutionIsolation() throws Exception {
        RetryPolicy policy = new RetryPolicy(failureClassifier, RetryDelayStrategy.noOp());
        int threadCount = 8;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<NodeExecutionResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final int retriesForThisNode = (index % 3) + 1; // 1, 2, or 3 retries
            WorkflowNode node = new WorkflowNode("node-iso-" + index, "custom", Map.of(
                    "retry", Map.of("enabled", true, "maxRetries", retriesForThisNode, "initialBackoffMs", 500L)
            ));
            ExecutionContext context = new ExecutionContext("exec-" + index, "wf-" + index, "user-" + index, Instant.now());

            futures.add(executorService.submit(() -> {
                startLatch.await();
                AtomicInteger localCounter = new AtomicInteger();
                NodeExecutor mockExecutor = new SimpleNodeExecutor(n -> {
                    int attempt = localCounter.incrementAndGet();
                    Instant now = Instant.now();
                    // Always fail until exhausted
                    return NodeExecutionResult.failure(n.getId(), n.getType(), now, now,
                            Map.of("threadIndex", index), Map.of("statusCode", 503), "HTTP 503 on thread " + index);
                });

                try {
                    return policy.executeWithRetry(node, Map.of("id", index), context, mockExecutor);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All concurrent executions should complete in time");
        executorService.shutdown();

        for (int i = 0; i < threadCount; i++) {
            int expectedRetries = (i % 3) + 1;
            int expectedAttempts = 1 + expectedRetries;
            NodeExecutionResult result = futures.get(i).get();

            assertEquals("node-iso-" + i, result.nodeId());
            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals(expectedRetries, result.retryCount(), "Retry count for thread " + i + " must not be affected by other threads");
            assertEquals(expectedAttempts, result.attempts().size(), "Attempts size for thread " + i + " must be strictly isolated");
            for (NodeExecutionAttempt attempt : result.attempts()) {
                assertEquals(i, attempt.getInput().get("threadIndex"), "Attempt input must belong to thread " + i);
            }
        }
    }
}
