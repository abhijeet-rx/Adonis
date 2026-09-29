package com.adonis.execution;

import com.adonis.model.NodeExecutionAttempt;
import com.adonis.model.RetryConfig;
import com.adonis.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
}
