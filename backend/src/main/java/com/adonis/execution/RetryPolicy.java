package com.adonis.execution;

import com.adonis.model.NodeExecutionAttempt;
import com.adonis.model.RetryConfig;
import com.adonis.model.WorkflowNode;
import com.adonis.util.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates synchronous retry and exponential backoff for node execution.
 *
 * Design constraints:
 *   - Completely separate from node executors (all node types automatically inherit retries).
 *   - Synchronous and in-process (Phase 6). Phase 7 introduces asynchronous worker pools.
 *   - Strictly local state per execution call (no shared mutable state across threads).
 *   - Granular attempt history tracking preserving all intermediate inputs, outputs, errors.
 *   - Pluggable RetryDelayStrategy for fast zero-sleep test execution.
 */
@Component
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final FailureClassifier failureClassifier;
    private final RetryDelayStrategy delayStrategy;

    @Autowired
    public RetryPolicy(FailureClassifier failureClassifier) {
        this(failureClassifier, RetryDelayStrategy.threadSleep());
    }

    public RetryPolicy(FailureClassifier failureClassifier, RetryDelayStrategy delayStrategy) {
        this.failureClassifier = failureClassifier != null ? failureClassifier : new FailureClassifier();
        this.delayStrategy = delayStrategy != null ? delayStrategy : RetryDelayStrategy.threadSleep();
    }

    /**
     * Executes the given node with configured retry and exponential backoff semantics.
     */
    public NodeExecutionResult executeWithRetry(
            WorkflowNode node,
            Map<String, Object> input,
            ExecutionContext context,
            NodeExecutor executor) {

        RetryConfig retryConfig = RetryConfig.fromNodeData(node.getData());
        int maxRetries = retryConfig.enabled() ? retryConfig.maxRetries() : 0;
        int maxAttempts = 1 + maxRetries;

        List<NodeExecutionAttempt> attempts = new ArrayList<>();
        Instant overallStart = Instant.now();

        for (int attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber++) {
            Instant attemptStart = Instant.now();
            NodeExecutionResult attemptResult = executor.execute(node, input, context);
            Instant attemptEnd = Instant.now();

            if (attemptResult.status() == ExecutionStatus.SUCCESS) {
                attempts.add(NodeExecutionAttempt.success(
                        attemptNumber,
                        attemptStart,
                        attemptEnd,
                        attemptResult.input(),
                        attemptResult.output()
                ));

                if (attemptNumber > 1) {
                    log.info("Workflow execution {} - Node '{}' [{}] Attempt {} succeeded after {} retries",
                            context.getExecutionId(), node.getId(), node.getType(), attemptNumber, attemptNumber - 1);
                } else {
                    log.debug("Workflow execution {} - Node '{}' [{}] succeeded on initial attempt",
                            context.getExecutionId(), node.getId(), node.getType());
                }

                return NodeExecutionResult.success(
                        node.getId(),
                        node.getType(),
                        overallStart,
                        attemptEnd,
                        attemptResult.input(),
                        attemptResult.output(),
                        attemptNumber - 1,
                        attempts
                );
            }

            if (attemptResult.status() == ExecutionStatus.FAILED) {
                attempts.add(NodeExecutionAttempt.failure(
                        attemptNumber,
                        attemptStart,
                        attemptEnd,
                        attemptResult.input(),
                        attemptResult.output(),
                        attemptResult.error()
                ));

                boolean retryable = failureClassifier.isRetryable(attemptResult);
                boolean retriesRemaining = attemptNumber < maxAttempts;

                if (retryable && retriesRemaining) {
                    long backoffMs = calculateBackoff(retryConfig, attemptNumber);
                    String sanitizedError = SecretRedactor.redactString(attemptResult.error());
                    log.info("Workflow execution {} - Node '{}' Attempt {} failed ({}). Retrying in {}ms (attempt {}/{})",
                            context.getExecutionId(), node.getId(), attemptNumber, sanitizedError, backoffMs, attemptNumber + 1, maxAttempts);

                    try {
                        delayStrategy.delay(backoffMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        String interruptedError = "Execution interrupted during retry backoff for node '" + node.getId() + "'";
                        log.warn("Workflow execution {} - Node '{}' was interrupted during backoff",
                                context.getExecutionId(), node.getId());
                        return NodeExecutionResult.failure(
                                node.getId(),
                                node.getType(),
                                overallStart,
                                Instant.now(),
                                attemptResult.input(),
                                attemptResult.output(),
                                interruptedError,
                                attemptNumber - 1,
                                attempts
                        );
                    }
                } else {
                    // Stop retrying (either non-retryable or retries exhausted)
                    String sanitizedError = SecretRedactor.redactString(attemptResult.error());
                    String finalError;

                    if (attempts.size() > 1) {
                        finalError = "Node failed after " + attempts.size() + " attempts: " + sanitizedError;
                        log.warn("Workflow execution {} - Node '{}' failed permanently after {} attempts: {}",
                                context.getExecutionId(), node.getId(), attempts.size(), sanitizedError);
                    } else {
                        finalError = sanitizedError;
                        if (!retryable && retryConfig.enabled()) {
                            log.info("Workflow execution {} - Node '{}' failed with non-retryable error, not retrying: {}",
                                    context.getExecutionId(), node.getId(), sanitizedError);
                        } else {
                            log.debug("Workflow execution {} - Node '{}' failed on initial attempt: {}",
                                    context.getExecutionId(), node.getId(), sanitizedError);
                        }
                    }

                    return NodeExecutionResult.failure(
                            node.getId(),
                            node.getType(),
                            overallStart,
                            attemptEnd,
                            attemptResult.input(),
                            attemptResult.output(),
                            finalError,
                            attempts.size() - 1,
                            attempts
                    );
                }
            } else if (attemptResult.status() == ExecutionStatus.SKIPPED) {
                return attemptResult;
            }
        }

        // Fallback for safety (should not be reached)
        return NodeExecutionResult.failure(
                node.getId(),
                node.getType(),
                overallStart,
                Instant.now(),
                input,
                "Node execution attempts exhausted",
                attempts.size(),
                attempts
        );
    }

    /**
     * Calculates exponential backoff delay with upper bound clamping and overflow protection.
     * Formula: delay = initialBackoffMs * multiplier^(retryNumber - 1)
     * e.g., retryNumber 1 -> initialBackoffMs * multiplier^0 = initialBackoffMs
     */
    public long calculateBackoff(RetryConfig config, int retryNumber) {
        if (config == null) {
            return RetryConfig.DEFAULT_INITIAL_BACKOFF_MS;
        }

        long initial = config.initialBackoffMs();
        double multiplier = config.backoffMultiplier();
        long maxBackoff = config.maxBackoffMs();

        if (retryNumber <= 1) {
            return Math.min(initial, maxBackoff);
        }

        double power = Math.pow(multiplier, retryNumber - 1);
        double rawDelay = initial * power;

        if (Double.isInfinite(rawDelay) || Double.isNaN(rawDelay) || rawDelay > maxBackoff) {
            return maxBackoff;
        }

        return Math.clamp((long) rawDelay, 0L, maxBackoff);
    }

    public FailureClassifier getFailureClassifier() {
        return failureClassifier;
    }

    public RetryDelayStrategy getDelayStrategy() {
        return delayStrategy;
    }
}
