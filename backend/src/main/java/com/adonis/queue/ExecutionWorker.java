package com.adonis.queue;

import com.adonis.execution.*;
import com.adonis.model.NodeExecution;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.util.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background asynchronous worker responsible for consuming execution jobs from Redis/Queue,
 * atomically transitioning execution state (QUEUED -> RUNNING), invoking the existing
 * WorkflowExecutionEngine, persisting results, and acknowledging processed messages.
 *
 * Implements crash recovery for unacknowledged pending messages and stale execution reconciliation.
 */
@Component
public class ExecutionWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWorker.class);

    private final ExecutionQueue queue;
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowExecutionValidator validator;
    private final WorkflowExecutionEngine engine;
    private final MongoTemplate mongoTemplate;

    private final boolean enabled;
    private final long pollTimeoutMs;
    private final long pendingClaimIdleMs;
    private final long staleExecutionTimeoutMs;
    private final long pendingCheckIntervalMs;
    private final String consumerName;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    @Autowired
    public ExecutionWorker(
            ExecutionQueue queue,
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine,
            MongoTemplate mongoTemplate,
            @Value("${adonis.worker.enabled:true}") boolean enabled,
            @Value("${adonis.worker.poll-timeout-ms:2000}") long pollTimeoutMs,
            @Value("${adonis.worker.pending-claim-idle-ms:60000}") long pendingClaimIdleMs,
            @Value("${adonis.worker.stale-execution-timeout-ms:300000}") long staleExecutionTimeoutMs,
            @Value("${adonis.worker.consumer-name:}") String consumerName) {
        this.queue = Objects.requireNonNull(queue, "ExecutionQueue must not be null");
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "WorkflowRepository must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "WorkflowExecutionRepository must not be null");
        this.validator = Objects.requireNonNull(validator, "WorkflowExecutionValidator must not be null");
        this.engine = Objects.requireNonNull(engine, "WorkflowExecutionEngine must not be null");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "MongoTemplate must not be null");
        this.enabled = enabled;
        this.pollTimeoutMs = Math.max(100, pollTimeoutMs);
        this.pendingClaimIdleMs = Math.max(1000, pendingClaimIdleMs);
        this.staleExecutionTimeoutMs = Math.max(1000, staleExecutionTimeoutMs);
        this.pendingCheckIntervalMs = 15000L;
        this.consumerName = (consumerName != null && !consumerName.isBlank()) ? consumerName : "worker-default";
    }

    public ExecutionWorker(
            ExecutionQueue queue,
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine,
            MongoTemplate mongoTemplate,
            boolean enabled,
            long pollTimeoutMs) {
        this(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                enabled, pollTimeoutMs, 60000L, 300000L, "worker-test");
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("ExecutionWorker is disabled by configuration");
            return;
        }
        if (running.compareAndSet(false, true)) {
            log.info("Starting ExecutionWorker [{}] background polling loop...", consumerName);
            workerThread = new Thread(this::runWorkerLoop, "adonis-execution-worker");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping ExecutionWorker [{}] gracefully...", consumerName);
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("ExecutionWorker stop interrupted while waiting for thread termination");
                }
            }
            log.info("ExecutionWorker stopped cleanly");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void runWorkerLoop() {
        long lastPendingCheckTime = 0;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // 1. Periodically check and reclaim stale pending unacknowledged messages
                long now = System.currentTimeMillis();
                if (now - lastPendingCheckTime >= pendingCheckIntervalMs) {
                    lastPendingCheckTime = now;
                    recoverPendingJobs();
                }

                // 2. Poll next message from the queue
                Optional<QueuedJobMessage> jobMsgOpt = queue.poll(Duration.ofMillis(pollTimeoutMs));
                if (jobMsgOpt.isPresent()) {
                    processJob(jobMsgOpt.get());
                }
            } catch (Exception ex) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("Unexpected error in execution worker loop; recovering after backoff", ex);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Inspects and reclaims stale unacknowledged messages in the consumer group's PEL
     * that were abandoned due to prior worker crash.
     */
    public void recoverPendingJobs() {
        try {
            List<QueuedJobMessage> pendingJobs = queue.claimPending(Duration.ofMillis(pendingClaimIdleMs), 10);
            if (pendingJobs != null && !pendingJobs.isEmpty()) {
                log.info("Claimed {} pending unacknowledged jobs for crash recovery", pendingJobs.size());
                for (QueuedJobMessage msg : pendingJobs) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    processJob(msg);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to claim pending jobs during recovery check", ex);
        }
    }

    /**
     * Convenience overload for legacy callers and unit tests.
     */
    public boolean processJob(ExecutionJob job) {
        if (job == null) {
            return false;
        }
        return processJob(new QueuedJobMessage("manual-" + job.executionId(), job, 1));
    }

    /**
     * Core worker execution logic:
     * 1. Verifies authoritative execution record exists in MongoDB.
     * 2. Checks current state:
     *    - If SUCCESS or FAILED: acknowledges duplicate delivery without re-executing.
     *    - If RUNNING: reconciles stale executions beyond timeout; otherwise avoids duplicate execution.
     *    - If QUEUED: atomically claims execution via findAndModify (QUEUED -> RUNNING).
     * 3. Executes the WorkflowExecutionEngine.
     * 4. Persists the sanitized SUCCESS or FAILED execution record to MongoDB.
     * 5. Acknowledges (XACK) the message ONLY AFTER successful persistence.
     *
     * @param message the queued job message containing messageId and job payload
     * @return true if the job was processed to terminal state, false if skipped or pending
     */
    public boolean processJob(QueuedJobMessage message) {
        if (message == null || message.job() == null) {
            return false;
        }

        String messageId = message.messageId();
        ExecutionJob job = message.job();
        String executionId = job.executionId();
        String workflowId = job.workflowId();

        log.info("Execution job claimed: executionId={}, workflowId={}, messageId={}, consumer={}",
                executionId, workflowId, messageId, consumerName);

        // 1. Verify execution exists in MongoDB
        Optional<WorkflowExecution> executionOpt = executionRepository.findById(executionId);
        if (executionOpt.isEmpty()) {
            log.warn("Execution record not found for executionId: {}. Acknowledging orphaned job to clear queue.", executionId);
            queue.acknowledge(messageId);
            return false;
        }

        WorkflowExecution existing = executionOpt.get();

        // 2. Check current status
        if (existing.getStatus() == ExecutionStatus.SUCCESS || existing.getStatus() == ExecutionStatus.FAILED) {
            log.info("Execution {} has already reached terminal status {}. Acknowledging duplicate delivery.",
                    executionId, existing.getStatus());
            queue.acknowledge(messageId);
            return false;
        }

        if (existing.getStatus() == ExecutionStatus.RUNNING) {
            Instant startedAt = existing.getStartedAt();
            long elapsedMs = (startedAt != null) ? Duration.between(startedAt, Instant.now()).toMillis() : Long.MAX_VALUE;

            if (elapsedMs > staleExecutionTimeoutMs) {
                log.warn("Execution {} has been RUNNING for {}ms (exceeding stale timeout {}ms). " +
                                "Marking FAILED to prevent duplicate side effects.",
                        executionId, elapsedMs, staleExecutionTimeoutMs);
                existing.markFailed(
                        Instant.now(),
                        existing.getNodeExecutions(),
                        "Execution timed out or processing worker terminated while RUNNING (exceeded " + staleExecutionTimeoutMs + "ms)"
                );
                executionRepository.save(existing);
                queue.acknowledge(messageId);
                return false;
            } else {
                log.info("Execution {} is currently RUNNING (elapsed {}ms <= {}ms). " +
                                "Skipping re-execution to prevent duplicate side effects.",
                        executionId, elapsedMs, staleExecutionTimeoutMs);
                return false;
            }
        }

        // 3. Atomic state transition: QUEUED -> RUNNING
        Query query = new Query(Criteria.where("_id").is(executionId).and("status").is(ExecutionStatus.QUEUED));
        Update update = new Update()
                .set("status", ExecutionStatus.RUNNING)
                .set("startedAt", Instant.now());
        WorkflowExecution claimed = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                WorkflowExecution.class
        );

        if (claimed == null) {
            log.info("Execution {} could not be claimed (raced with another worker or status changed).", executionId);
            return false;
        }

        log.info("Execution started: executionId={}, workflowId={}", executionId, workflowId);

        // 4. Load workflow from MongoDB
        Optional<Workflow> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            log.error("Workflow not found for workflowId: {} during execution: {}", workflowId, executionId);
            claimed.markFailed(Instant.now(), Collections.emptyList(), "Workflow not found with id: " + workflowId);
            executionRepository.save(claimed);
            queue.acknowledge(messageId);
            return true;
        }

        Workflow workflow = workflowOpt.get();

        // 5. Validate graph ordering
        List<WorkflowNode> executionOrder;
        try {
            executionOrder = validator.validateAndOrder(workflow);
        } catch (Exception ex) {
            log.error("Workflow graph validation failed for execution: {}", executionId, ex);
            claimed.markFailed(Instant.now(), Collections.emptyList(), "Workflow validation failed: " + ex.getMessage());
            executionRepository.save(claimed);
            queue.acknowledge(messageId);
            return true;
        }

        // 6. Execute via WorkflowExecutionEngine
        WorkflowExecutionResult engineResult;
        try {
            engineResult = engine.execute(workflow, executionOrder, job.userId(), executionId);
        } catch (Exception ex) {
            log.error("Execution engine failure for execution: {}", executionId, ex);
            Instant completedAt = Instant.now();
            String sanitizedError = SecretRedactor.redactString("Execution engine failure: " + ex.getMessage());
            claimed.markFailed(completedAt, Collections.emptyList(), sanitizedError);
            try {
                executionRepository.save(claimed);
                queue.acknowledge(messageId);
            } catch (Exception saveEx) {
                log.error("Failed to persist failed execution result for execution: {}", executionId, saveEx);
            }
            return true;
        }

        // 7. Sanitize and persist final state
        WorkflowExecutionResult sanitizedResult = SecretRedactor.sanitize(engineResult);
        List<NodeExecution> nodeExecutions = buildNodeExecutions(sanitizedResult.nodes(), executionOrder);

        if (sanitizedResult.status() == ExecutionStatus.SUCCESS) {
            claimed.markSuccess(sanitizedResult.completedAt(), nodeExecutions);
            log.info("Execution completed successfully: executionId={}", executionId);
        } else {
            claimed.markFailed(sanitizedResult.completedAt(), nodeExecutions, sanitizedResult.error());
            log.warn("Execution failed: executionId={}, error={}", executionId, sanitizedResult.error());
        }

        try {
            executionRepository.save(claimed);
            // CRITICAL: Acknowledge ONLY after successful persistence
            queue.acknowledge(messageId);
        } catch (Exception saveEx) {
            log.error("CRITICAL: Failed to persist execution result for executionId: {}. Message [{}] not ACKed.",
                    executionId, messageId, saveEx);
            throw saveEx;
        }

        return true;
    }

    private List<NodeExecution> buildNodeExecutions(List<NodeExecutionResult> executedNodes, List<WorkflowNode> executionOrder) {
        List<NodeExecution> results = new ArrayList<>();
        Set<String> executedIds = new HashSet<>();

        if (executedNodes != null) {
            for (NodeExecutionResult res : executedNodes) {
                executedIds.add(res.nodeId());
                Map<String, Object> sanitizedInput = SecretRedactor.redactMap(res.input());
                Map<String, Object> sanitizedOutput = SecretRedactor.redactMap(res.output());
                String sanitizedError = SecretRedactor.redactString(res.error());

                NodeExecution nodeExec = new NodeExecution(
                        res.nodeId(),
                        res.nodeType(),
                        res.status(),
                        res.startedAt(),
                        res.completedAt(),
                        res.durationMs(),
                        sanitizedInput,
                        sanitizedOutput,
                        sanitizedError,
                        res.retryCount(),
                        res.attempts()
                );
                results.add(nodeExec);
            }
        }

        if (executionOrder != null) {
            for (WorkflowNode node : executionOrder) {
                if (!executedIds.contains(node.getId())) {
                    results.add(NodeExecution.skipped(node.getId(), node.getType()));
                }
            }
        }

        return results;
    }

    public long getStaleExecutionTimeoutMs() {
        return staleExecutionTimeoutMs;
    }

    public long getPendingClaimIdleMs() {
        return pendingClaimIdleMs;
    }
}
