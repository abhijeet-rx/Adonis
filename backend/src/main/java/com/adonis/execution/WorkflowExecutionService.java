package com.adonis.execution;

import com.adonis.dto.ExecuteWorkflowResponse;
import com.adonis.dto.ExecutionResponse;
import com.adonis.dto.ExecutionSummaryResponse;
import com.adonis.dto.PageResponse;
import com.adonis.exception.ExecutionNotFoundException;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.NodeExecution;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowTriggerType;
import com.adonis.queue.ExecutionJob;
import com.adonis.queue.ExecutionQueue;
import com.adonis.queue.QueueSubmissionException;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.util.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class WorkflowExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionValidator validator;
    private final WorkflowExecutionEngine engine;
    private final WorkflowExecutionRepository executionRepository;
    private final ExecutionQueue executionQueue;

    public WorkflowExecutionService(
            WorkflowRepository workflowRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine,
            WorkflowExecutionRepository executionRepository) {
        this(workflowRepository, validator, engine, executionRepository, null);
    }

    @Autowired
    public WorkflowExecutionService(
            WorkflowRepository workflowRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine,
            WorkflowExecutionRepository executionRepository,
            @Autowired(required = false) ExecutionQueue executionQueue) {
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "WorkflowRepository must not be null");
        this.validator = Objects.requireNonNull(validator, "WorkflowExecutionValidator must not be null");
        this.engine = Objects.requireNonNull(engine, "WorkflowExecutionEngine must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "WorkflowExecutionRepository must not be null");
        this.executionQueue = executionQueue;
    }

    /**
     * Asynchronously enqueues a manual workflow execution:
     * 1. Authenticates & verifies ownership
     * 2. Validates workflow graph structure upfront
     * 3. Creates execution record in QUEUED status with MANUAL trigger
     * 4. Enqueues job to Redis execution queue
     * 5. Returns 202 Accepted response payload
     */
    public ExecuteWorkflowResponse enqueueExecution(String workflowId, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + workflowId));

        List<WorkflowNode> executionOrder = validator.validateAndOrder(workflow);
        String triggerType = determineTriggerType(executionOrder);

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), userId, triggerType);
        Map<String, Object> manualPayload = new LinkedHashMap<>();
        manualPayload.put("type", "MANUAL");
        manualPayload.put("triggeredAt", Instant.now().toString());
        manualPayload.put("userId", userId);
        execution.setTriggerPayload(manualPayload);

        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        if (executionQueue == null) {
            log.error("ExecutionQueue is not configured; cannot enqueue execution: {}", executionId);
            execution.markQueueFailed("Execution queue service is unavailable");
            executionRepository.save(execution);
            throw new QueueSubmissionException("Execution queue is not configured");
        }

        ExecutionJob job = new ExecutionJob(executionId, workflow.getId(), userId, triggerType, Instant.now());
        try {
            executionQueue.enqueue(job);
            log.info("Workflow execution enqueued: executionId={}, workflowId={}", executionId, workflow.getId());
        } catch (Exception ex) {
            log.error("Failed to enqueue execution job for executionId: {}", executionId, ex);
            execution.markQueueFailed("Failed to queue workflow execution");
            executionRepository.save(execution);
            throw new QueueSubmissionException("Failed to enqueue workflow execution", ex);
        }

        return ExecuteWorkflowResponse.queued(executionId, workflow.getId());
    }

    /**
     * Asynchronously enqueues a scheduled workflow execution through the Redis execution queue.
     *
     * @param workflow the scheduled workflow
     * @param occurrenceKey unique idempotency occurrence key
     * @param scheduledFireTime calculated schedule fire time
     * @return 202 Accepted ExecuteWorkflowResponse
     */
    public ExecuteWorkflowResponse enqueueScheduledExecution(Workflow workflow, String occurrenceKey, Instant scheduledFireTime) {
        Objects.requireNonNull(workflow, "Workflow must not be null");
        validator.validateAndOrder(workflow);
        String triggerType = WorkflowTriggerType.SCHEDULE.name();

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), workflow.getUserId(), triggerType);
        execution.setScheduledOccurrence(occurrenceKey);

        Map<String, Object> schedulePayload = new LinkedHashMap<>();
        schedulePayload.put("type", "SCHEDULE");
        schedulePayload.put("scheduledFireTime", scheduledFireTime != null ? scheduledFireTime.toString() : Instant.now().toString());
        if (workflow.getTriggerConfig() != null) {
            schedulePayload.put("cronExpression", workflow.getTriggerConfig().getCronExpression());
            schedulePayload.put("timezone", workflow.getTriggerConfig().getTimezone());
        }
        execution.setTriggerPayload(schedulePayload);

        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        if (executionQueue == null) {
            log.error("ExecutionQueue is not configured; cannot enqueue scheduled execution: {}", executionId);
            execution.markQueueFailed("Execution queue service is unavailable");
            executionRepository.save(execution);
            throw new QueueSubmissionException("Execution queue is not configured");
        }

        ExecutionJob job = new ExecutionJob(executionId, workflow.getId(), workflow.getUserId(), triggerType, Instant.now());
        try {
            executionQueue.enqueue(job);
            log.info("Scheduled workflow queued: executionId={}, workflowId={}, occurrenceKey={}",
                    executionId, workflow.getId(), occurrenceKey);
        } catch (Exception ex) {
            log.error("Failed to enqueue scheduled job for executionId: {}", executionId, ex);
            execution.markQueueFailed("Failed to queue scheduled execution");
            executionRepository.save(execution);
            throw new QueueSubmissionException("Failed to enqueue scheduled execution", ex);
        }

        return ExecuteWorkflowResponse.queued(executionId, workflow.getId());
    }

    /**
     * Asynchronously enqueues a webhook-triggered workflow execution through the Redis execution queue,
     * enforcing duplicate delivery protection via optional Idempotency-Key.
     *
     * @param workflow the active webhook-triggered workflow
     * @param idempotencyKey optional idempotency key
     * @param triggerPayload sanitized webhook payload
     * @return 202 Accepted ExecuteWorkflowResponse
     */
    public ExecuteWorkflowResponse enqueueWebhookExecution(
            Workflow workflow,
            String idempotencyKey,
            Map<String, Object> triggerPayload) {
        Objects.requireNonNull(workflow, "Workflow must not be null");

        String safeKey = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
        if (safeKey != null) {
            Optional<WorkflowExecution> existingOpt = executionRepository.findByWorkflowIdAndIdempotencyKey(workflow.getId(), safeKey);
            if (existingOpt.isPresent()) {
                WorkflowExecution existing = existingOpt.get();
                log.info("Duplicate webhook idempotency key ignored: workflowId={}, idempotencyKey={}, executionId={}",
                        workflow.getId(), safeKey, existing.getId());
                return new ExecuteWorkflowResponse(existing.getId(), workflow.getId(), existing.getStatus());
            }
        }

        validator.validateAndOrder(workflow);
        String triggerType = WorkflowTriggerType.WEBHOOK.name();

        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), workflow.getUserId(), triggerType);
        execution.setIdempotencyKey(safeKey);
        execution.setTriggerPayload(triggerPayload != null ? triggerPayload : Map.of());

        try {
            execution = executionRepository.save(execution);
        } catch (DuplicateKeyException ex) {
            if (safeKey != null) {
                Optional<WorkflowExecution> existingOpt = executionRepository.findByWorkflowIdAndIdempotencyKey(workflow.getId(), safeKey);
                if (existingOpt.isPresent()) {
                    WorkflowExecution existing = existingOpt.get();
                    log.info("Concurrent duplicate webhook idempotency key caught: workflowId={}, idempotencyKey={}, executionId={}",
                            workflow.getId(), safeKey, existing.getId());
                    return new ExecuteWorkflowResponse(existing.getId(), workflow.getId(), existing.getStatus());
                }
            }
            throw ex;
        }

        String executionId = execution.getId();

        if (executionQueue == null) {
            log.error("ExecutionQueue is not configured; cannot enqueue webhook execution: {}", executionId);
            execution.markQueueFailed("Execution queue service is unavailable");
            executionRepository.save(execution);
            throw new QueueSubmissionException("Execution queue is not configured");
        }

        ExecutionJob job = new ExecutionJob(executionId, workflow.getId(), workflow.getUserId(), triggerType, Instant.now());
        try {
            executionQueue.enqueue(job);
            log.info("Webhook execution queued: executionId={}, workflowId={}, idempotencyKey={}",
                    executionId, workflow.getId(), safeKey);
        } catch (Exception ex) {
            log.error("Failed to enqueue webhook job for executionId: {}", executionId, ex);
            execution.markQueueFailed("Failed to queue webhook execution");
            executionRepository.save(execution);
            throw new QueueSubmissionException("Failed to enqueue webhook execution", ex);
        }

        return ExecuteWorkflowResponse.queued(executionId, workflow.getId());
    }

    /**
     * Synchronously executes workflow in-process (used for testing and immediate synchronous runs).
     */
    public WorkflowExecutionResult executeWorkflow(String workflowId, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + workflowId));

        List<WorkflowNode> executionOrder = validator.validateAndOrder(workflow);
        String triggerType = WorkflowTriggerType.MANUAL.name();

        // 1. Create and persist initial execution record (RUNNING)
        WorkflowExecution execution = WorkflowExecution.start(workflow.getId(), userId, triggerType);
        Map<String, Object> manualPayload = new LinkedHashMap<>();
        manualPayload.put("type", "MANUAL");
        manualPayload.put("userId", userId);
        execution.setTriggerPayload(manualPayload);

        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        // 2. Execute via in-process engine
        WorkflowExecutionResult engineResult;
        TriggerContext triggerContext = TriggerContext.manual(userId);
        try {
            engineResult = engine.execute(workflow, executionOrder, userId, executionId, triggerContext);
        } catch (Exception ex) {
            Instant completedAt = Instant.now();
            String sanitizedError = SecretRedactor.redactString("Execution engine failure: " + ex.getMessage());
            execution.markFailed(completedAt, Collections.emptyList(), sanitizedError);
            executionRepository.save(execution);
            throw ex;
        }

        // 3. Transform node execution results with secret redaction
        WorkflowExecutionResult sanitizedResult = SecretRedactor.sanitize(engineResult);
        List<NodeExecution> nodeExecutions = buildNodeExecutions(sanitizedResult.nodes(), executionOrder);

        // 4. Update and persist final execution state (SUCCESS or FAILED)
        if (sanitizedResult.status() == ExecutionStatus.SUCCESS) {
            execution.markSuccess(sanitizedResult.completedAt(), nodeExecutions);
        } else {
            execution.markFailed(sanitizedResult.completedAt(), nodeExecutions, sanitizedResult.error());
        }
        executionRepository.save(execution);

        return sanitizedResult;
    }

    public ExecutionResponse getExecution(String executionId, String userId) {
        WorkflowExecution execution = executionRepository.findByIdAndUserId(executionId, userId)
                .orElseThrow(() -> new ExecutionNotFoundException("Execution not found with id: " + executionId));

        return ExecutionResponse.fromModel(execution);
    }

    public PageResponse<ExecutionSummaryResponse> getWorkflowExecutions(String workflowId, String userId, Pageable pageable) {
        workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + workflowId));

        Page<WorkflowExecution> page = executionRepository.findByWorkflowIdAndUserId(workflowId, userId, pageable);
        List<ExecutionSummaryResponse> summaryList = page.getContent().stream()
                .map(ExecutionSummaryResponse::fromModel)
                .toList();

        return new PageResponse<>(
                summaryList,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    public PageResponse<ExecutionSummaryResponse> getUserExecutions(String userId, ExecutionStatus status, Pageable pageable) {
        Page<WorkflowExecution> page;
        if (status != null) {
            page = executionRepository.findByUserIdAndStatus(userId, status, pageable);
        } else {
            page = executionRepository.findAllByUserId(userId, pageable);
        }

        List<ExecutionSummaryResponse> summaryList = page.getContent().stream()
                .map(ExecutionSummaryResponse::fromModel)
                .toList();

        return new PageResponse<>(
                summaryList,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
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

    private String determineTriggerType(List<WorkflowNode> executionOrder) {
        if (executionOrder == null || executionOrder.isEmpty()) {
            return "manual";
        }
        WorkflowNode firstNode = executionOrder.get(0);
        if (firstNode.getData() != null && firstNode.getData().containsKey("triggerType")) {
            Object type = firstNode.getData().get("triggerType");
            return type != null ? type.toString() : "manual";
        }
        return "manual";
    }
}
