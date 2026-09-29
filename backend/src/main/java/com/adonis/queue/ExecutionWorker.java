package com.adonis.queue;

import com.adonis.execution.*;
import com.adonis.model.NodeExecution;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.util.SecretRedactor;
import com.mongodb.client.result.UpdateResult;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background asynchronous worker responsible for consuming execution jobs from Redis/Queue,
 * atomically transitioning execution state (QUEUED -> RUNNING) with an ownership lease,
 * periodically renewing the lease via heartbeat, invoking the existing WorkflowExecutionEngine,
 * persisting results, and acknowledging processed messages.
 *
 * Implements lease-based crash recovery for unacknowledged pending messages and stale executions.
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
    private final long leaseDurationMs;
    private final long heartbeatIntervalMs;
    private final long pendingCheckIntervalMs;
    private final String consumerName;
    private final String workerId;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    private final ScheduledExecutorService heartbeatScheduler;
    private final ConcurrentMap<String, ScheduledFuture<?>> activeHeartbeats = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicBoolean> ownershipLostFlags = new ConcurrentHashMap<>();

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
            @Value("${adonis.worker.lease-duration-ms:${adonis.worker.stale-execution-timeout-ms:60000}}") long leaseDurationMs,
            @Value("${adonis.worker.heartbeat-interval-ms:20000}") long heartbeatIntervalMs,
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
        this.leaseDurationMs = Math.max(1000, leaseDurationMs);

        long validatedHeartbeat = Math.max(500, heartbeatIntervalMs);
        if (validatedHeartbeat >= this.leaseDurationMs) {
            log.warn("Configured heartbeatIntervalMs ({}) >= leaseDurationMs ({}). Clamping heartbeat interval to leaseDurationMs / 3.",
                    validatedHeartbeat, this.leaseDurationMs);
            validatedHeartbeat = Math.max(500, this.leaseDurationMs / 3);
        }
        this.heartbeatIntervalMs = validatedHeartbeat;
        this.pendingCheckIntervalMs = 15000L;

        if (consumerName != null && !consumerName.isBlank()) {
            this.consumerName = consumerName.trim();
            this.workerId = this.consumerName;
        } else {
            String generated = "worker-" + UUID.randomUUID();
            this.consumerName = generated;
            this.workerId = generated;
        }

        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "adonis-worker-heartbeat-" + workerId);
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutionWorker(
            ExecutionQueue queue,
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine,
            MongoTemplate mongoTemplate,
            boolean enabled,
            long pollTimeoutMs,
            long pendingClaimIdleMs,
            long leaseDurationMs,
            String consumerName) {
        this(queue, workflowRepository, executionRepository, validator, engine, mongoTemplate,
                enabled, pollTimeoutMs, pendingClaimIdleMs, leaseDurationMs, Math.max(500, leaseDurationMs / 3), consumerName);
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
                enabled, pollTimeoutMs, 60000L, 60000L, 20000L, "worker-test");
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("ExecutionWorker is disabled by configuration");
            return;
        }
        if (running.compareAndSet(false, true)) {
            log.info("Starting ExecutionWorker [{}] background polling loop...", workerId);
            workerThread = new Thread(this::runWorkerLoop, "adonis-execution-worker");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping ExecutionWorker [{}] gracefully...", workerId);
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("ExecutionWorker stop interrupted while waiting for thread termination");
                }
            }
            heartbeatScheduler.shutdownNow();
            try {
                if (!heartbeatScheduler.awaitTermination(2000, TimeUnit.MILLISECONDS)) {
                    log.warn("ExecutionWorker heartbeat scheduler did not terminate within timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
     *    - If RUNNING:
     *        * If leaseUntil > now: active lease held by an existing worker; skip without executing or ACKing.
     *        * If leaseUntil <= now: expired lease; atomically take over ownership. Only winning worker
     *          transitions state to FAILED with recovery diagnostic, persists, and ACKs message.
     *    - If QUEUED: atomically claims execution via findAndModify (QUEUED -> RUNNING) setting ownership lease.
     * 3. Starts background heartbeat to periodically extend the lease.
     * 4. Executes the WorkflowExecutionEngine.
     * 5. Persists the sanitized SUCCESS or FAILED execution record to MongoDB (if ownership was not lost).
     * 6. Acknowledges (XACK) the message ONLY AFTER successful persistence.
     *
     * @param message the queued job message containing messageId and job payload
     * @return true if the job was processed through the engine, false if skipped or recovered
     */
    public boolean processJob(QueuedJobMessage message) {
        if (message == null || message.job() == null) {
            return false;
        }

        String messageId = message.messageId();
        ExecutionJob job = message.job();
        String executionId = job.executionId();
        String workflowId = job.workflowId();

        log.info("Execution job claimed: executionId={}, workflowId={}, messageId={}, workerId={}",
                executionId, workflowId, messageId, workerId);

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
            Instant now = Instant.now();
            Instant leaseUntil = existing.getLeaseUntil();

            // Case A: Lease is still valid
            if (leaseUntil != null && leaseUntil.isAfter(now)) {
                log.info("Execution {} is actively RUNNING with valid lease until {} (owner={}). Skipping to avoid duplicate side effects.",
                        executionId, leaseUntil, existing.getWorkerId());
                return false;
            }

            // Case B: Lease expired (or was never set on a legacy execution)
            log.warn("Execution {} has an expired lease (leaseUntil={}, owner={}). Attempting atomic takeover.",
                    executionId, leaseUntil, existing.getWorkerId());

            Criteria expiredCriteria = new Criteria().orOperator(
                    Criteria.where("leaseUntil").lte(now),
                    Criteria.where("leaseUntil").is(null)
            );
            Query takeoverQuery = new Query(Criteria.where("_id").is(executionId)
                    .and("status").is(ExecutionStatus.RUNNING)
                    .andOperator(expiredCriteria));

            Instant newLeaseUntil = now.plusMillis(leaseDurationMs);
            Update takeoverUpdate = new Update()
                    .set("workerId", workerId)
                    .set("leaseUntil", newLeaseUntil)
                    .set("lastHeartbeatAt", now);

            WorkflowExecution acquired = mongoTemplate.findAndModify(
                    takeoverQuery,
                    takeoverUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    WorkflowExecution.class
            );

            if (acquired == null) {
                log.info("Worker [{}] lost race to acquire expired lease for execution [{}]. Another worker took over or status transitioned.",
                        workerId, executionId);
                return false;
            }

            // Successfully acquired expired lease!
            String prevOwner = existing.getWorkerId() != null ? existing.getWorkerId() : "unknown";
            log.warn("Worker [{}] acquired expired lease for execution [{}] (previous worker was {}). Marking FAILED to prevent duplicate side effects.",
                    workerId, executionId, prevOwner);

            String recoveryError = String.format(
                    "Execution lease expired (previous worker [%s] lost ownership or terminated); marked FAILED during recovery to prevent duplicate external side effects",
                    prevOwner
            );

            acquired.markFailed(now, acquired.getNodeExecutions(), recoveryError);

            try {
                executionRepository.save(acquired);
                queue.acknowledge(messageId);
                log.info("Execution [{}] recovery complete: marked FAILED and message [{}] acknowledged.", executionId, messageId);
            } catch (Exception saveEx) {
                log.error("Failed to persist failed recovery state for execution [{}]. Message [{}] not ACKed.",
                        executionId, messageId, saveEx);
                throw saveEx;
            }

            return false;
        }

        // 3. Atomic state transition: QUEUED -> RUNNING with initial ownership lease
        Instant now = Instant.now();
        Instant leaseUntil = now.plusMillis(leaseDurationMs);

        Query claimQuery = new Query(Criteria.where("_id").is(executionId).and("status").is(ExecutionStatus.QUEUED));
        Update claimUpdate = new Update()
                .set("status", ExecutionStatus.RUNNING)
                .set("startedAt", now)
                .set("workerId", workerId)
                .set("leaseUntil", leaseUntil)
                .set("lastHeartbeatAt", now);

        WorkflowExecution claimed = mongoTemplate.findAndModify(
                claimQuery,
                claimUpdate,
                FindAndModifyOptions.options().returnNew(true),
                WorkflowExecution.class
        );

        if (claimed == null) {
            log.info("Execution {} could not be claimed (raced with another worker or status changed).", executionId);
            return false;
        }

        log.info("Execution started: executionId={}, workflowId={}, workerId={}", executionId, workflowId, workerId);

        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        startHeartbeat(executionId, ownershipLost);

        try {
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

                if (!ownershipLost.get()) {
                    try {
                        executionRepository.save(claimed);
                        queue.acknowledge(messageId);
                    } catch (Exception saveEx) {
                        log.error("Failed to persist failed execution result for execution: {}", executionId, saveEx);
                    }
                } else {
                    log.warn("Worker [{}] lost ownership of execution [{}] during engine failure. Skipping persist and ACK.",
                            workerId, executionId);
                }
                return true;
            }

            // Check if ownership was lost during execution before persisting final state
            if (ownershipLost.get()) {
                log.warn("Worker [{}] lost ownership of execution [{}] during execution. Aborting persist and ACK to prevent overwriting takeover.",
                        workerId, executionId);
                return false;
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
        } finally {
            stopHeartbeat(executionId);
        }
    }

    /**
     * Schedules periodic lease renewal for the given executionId.
     * The heartbeat task will run at a fixed rate of heartbeatIntervalMs.
     */
    void startHeartbeat(String executionId, AtomicBoolean ownershipLost) {
        ownershipLostFlags.put(executionId, ownershipLost);
        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                renewLease(executionId, ownershipLost);
            } catch (Exception ex) {
                log.warn("Uncaught exception in heartbeat task for execution [{}]: {}", executionId, ex.getMessage());
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> oldFuture = activeHeartbeats.put(executionId, future);
        if (oldFuture != null) {
            oldFuture.cancel(false);
        }
    }

    /**
     * Cancels any scheduled heartbeat for the given executionId and cleans up resources.
     */
    void stopHeartbeat(String executionId) {
        ScheduledFuture<?> future = activeHeartbeats.remove(executionId);
        if (future != null) {
            future.cancel(false);
        }
        ownershipLostFlags.remove(executionId);
    }

    /**
     * Atomically renews the lease in MongoDB for the current execution IF AND ONLY IF
     * the status is still RUNNING and the current worker is still the owner (workerId matches).
     *
     * @param executionId the execution id to renew
     * @return true if renewed, false if ownership was lost
     */
    public boolean renewLease(String executionId) {
        AtomicBoolean flag = ownershipLostFlags.get(executionId);
        return renewLease(executionId, flag != null ? flag : new AtomicBoolean(false));
    }

    boolean renewLease(String executionId, AtomicBoolean ownershipLost) {
        try {
            Instant now = Instant.now();
            Instant newLeaseUntil = now.plusMillis(leaseDurationMs);

            Query query = new Query(Criteria.where("_id").is(executionId)
                    .and("status").is(ExecutionStatus.RUNNING)
                    .and("workerId").is(workerId));

            Update update = new Update()
                    .set("leaseUntil", newLeaseUntil)
                    .set("lastHeartbeatAt", now);

            UpdateResult result = mongoTemplate.updateFirst(query, update, WorkflowExecution.class);

            if (result.getMatchedCount() == 0) {
                log.warn("Heartbeat failed: Worker [{}] lost ownership of execution [{}] (matchedCount=0). Stopping heartbeat.",
                        workerId, executionId);
                if (ownershipLost != null) {
                    ownershipLost.set(true);
                }
                stopHeartbeat(executionId);
                return false;
            }

            log.debug("Heartbeat renewed lease for execution [{}] until {}", executionId, newLeaseUntil);
            return true;
        } catch (Exception ex) {
            // Transient MongoDB failure: log sanitized warning, do not immediately fail execution
            log.warn("Transient failure renewing lease heartbeat for execution [{}] by worker [{}]: {}",
                    executionId, workerId, ex.getMessage());
            return false;
        }
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

    public long getLeaseDurationMs() {
        return leaseDurationMs;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public long getPendingClaimIdleMs() {
        return pendingClaimIdleMs;
    }

    public long getStaleExecutionTimeoutMs() {
        return leaseDurationMs;
    }
}
