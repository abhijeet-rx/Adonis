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
 * Background asynchronous worker responsible for polling execution jobs from Redis/Queue,
 * atomically transitioning execution state (QUEUED -> RUNNING), invoking the existing
 * WorkflowExecutionEngine, and persisting the final execution results.
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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public ExecutionWorker(
            ExecutionQueue queue,
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository executionRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine,
            MongoTemplate mongoTemplate,
            @Value("${adonis.worker.enabled:true}") boolean enabled,
            @Value("${adonis.worker.poll-timeout-ms:2000}") long pollTimeoutMs) {
        this.queue = Objects.requireNonNull(queue, "ExecutionQueue must not be null");
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "WorkflowRepository must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "WorkflowExecutionRepository must not be null");
        this.validator = Objects.requireNonNull(validator, "WorkflowExecutionValidator must not be null");
        this.engine = Objects.requireNonNull(engine, "WorkflowExecutionEngine must not be null");
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "MongoTemplate must not be null");
        this.enabled = enabled;
        this.pollTimeoutMs = Math.max(100, pollTimeoutMs);
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("ExecutionWorker is disabled by configuration");
            return;
        }
        if (running.compareAndSet(false, true)) {
            log.info("Starting ExecutionWorker background polling loop...");
            workerThread = new Thread(this::runWorkerLoop, "adonis-execution-worker");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping ExecutionWorker gracefully...");
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("ExecutionWorker stop interrupted while joining thread");
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
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<ExecutionJob> jobOpt = queue.poll(Duration.ofMillis(pollTimeoutMs));
                if (jobOpt.isPresent()) {
                    processJob(jobOpt.get());
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
     * Core worker execution logic. Validates execution state, performs atomic state
     * transition from QUEUED to RUNNING, executes the engine, and updates execution record.
     *
     * @param job the deserialized execution job
     * @return true if the job was processed or safely handled, false if missing/duplicate
     */
    public boolean processJob(ExecutionJob job) {
        if (job == null) {
            return false;
        }

        String executionId = job.executionId();
        String workflowId = job.workflowId();
        log.info("Execution job received: executionId={}, workflowId={}", executionId, workflowId);

        // 1. Verify execution exists
        Optional<WorkflowExecution> executionOpt = executionRepository.findById(executionId);
        if (executionOpt.isEmpty()) {
            log.warn("Execution record not found for executionId: {}. Skipping job.", executionId);
            return false;
        }

        WorkflowExecution existing = executionOpt.get();
        if (existing.getStatus() != ExecutionStatus.QUEUED) {
            log.info("Execution {} is already in status {}. Skipping duplicate job delivery.",
                    executionId, existing.getStatus());
            return false;
        }

        // 2. Atomic state transition: QUEUED -> RUNNING
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
            log.info("Execution {} could not be claimed (already claimed or completed by another worker). Skipping.",
                    executionId);
            return false;
        }

        log.info("Execution started: executionId={}, workflowId={}", executionId, workflowId);

        // 3. Load workflow from MongoDB
        Optional<Workflow> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            log.error("Workflow not found for workflowId: {} during execution: {}", workflowId, executionId);
            claimed.markFailed(Instant.now(), Collections.emptyList(), "Workflow not found with id: " + workflowId);
            executionRepository.save(claimed);
            return true;
        }

        Workflow workflow = workflowOpt.get();

        // 4. Validate graph ordering
        List<WorkflowNode> executionOrder;
        try {
            executionOrder = validator.validateAndOrder(workflow);
        } catch (Exception ex) {
            log.error("Workflow graph validation failed for execution: {}", executionId, ex);
            claimed.markFailed(Instant.now(), Collections.emptyList(), "Workflow validation failed: " + ex.getMessage());
            executionRepository.save(claimed);
            return true;
        }

        // 5. Execute via WorkflowExecutionEngine
        WorkflowExecutionResult engineResult;
        try {
            engineResult = engine.execute(workflow, executionOrder, job.userId(), executionId);
        } catch (Exception ex) {
            log.error("Execution engine failure for execution: {}", executionId, ex);
            Instant completedAt = Instant.now();
            String sanitizedError = SecretRedactor.redactString("Execution engine failure: " + ex.getMessage());
            claimed.markFailed(completedAt, Collections.emptyList(), sanitizedError);
            executionRepository.save(claimed);
            return true;
        }

        // 6. Sanitize and persist final state
        WorkflowExecutionResult sanitizedResult = SecretRedactor.sanitize(engineResult);
        List<NodeExecution> nodeExecutions = buildNodeExecutions(sanitizedResult.nodes(), executionOrder);

        if (sanitizedResult.status() == ExecutionStatus.SUCCESS) {
            claimed.markSuccess(sanitizedResult.completedAt(), nodeExecutions);
            log.info("Execution completed successfully: executionId={}", executionId);
        } else {
            claimed.markFailed(sanitizedResult.completedAt(), nodeExecutions, sanitizedResult.error());
            log.warn("Execution failed: executionId={}, error={}", executionId, sanitizedResult.error());
        }

        executionRepository.save(claimed);
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
}
