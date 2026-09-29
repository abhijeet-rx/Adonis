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
import com.adonis.queue.ExecutionJob;
import com.adonis.queue.ExecutionQueue;
import com.adonis.queue.QueueSubmissionException;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.util.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
     * Asynchronously enqueues a workflow execution:
     * 1. Authenticates & verifies ownership
     * 2. Validates workflow graph structure upfront
     * 3. Creates execution record in QUEUED status
     * 4. Enqueues job to Redis execution queue
     * 5. Handles queue failure gracefully by marking execution FAILED in MongoDB
     * 6. Returns 202 Accepted response payload
     *
     * @param workflowId the workflow ID
     * @param userId the authenticated user ID
     * @return asynchronous ExecuteWorkflowResponse containing executionId, workflowId, and QUEUED status
     */
    public ExecuteWorkflowResponse enqueueExecution(String workflowId, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + workflowId));

        List<WorkflowNode> executionOrder = validator.validateAndOrder(workflow);
        String triggerType = determineTriggerType(executionOrder);

        // 1. Create and persist initial execution record (QUEUED)
        WorkflowExecution execution = WorkflowExecution.queued(workflow.getId(), userId, triggerType);
        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        // 2. Enqueue job
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
     * Loads the workflow verifying ownership, validates its graph structure,
     * persists an initial RUNNING execution record, sequentially executes nodes,
     * updates the record to SUCCESS or FAILED with node-by-node details (including SKIPPED downstream nodes),
     * and returns the deeply sanitized final execution result.
     *
     * @param workflowId the workflow ID
     * @param userId the authenticated user ID
     * @return sanitized WorkflowExecutionResult
     */
    public WorkflowExecutionResult executeWorkflow(String workflowId, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + workflowId));

        List<WorkflowNode> executionOrder = validator.validateAndOrder(workflow);
        String triggerType = determineTriggerType(executionOrder);

        // 1. Create and persist initial execution record (RUNNING)
        WorkflowExecution execution = WorkflowExecution.start(workflow.getId(), userId, triggerType);
        execution = executionRepository.save(execution);
        String executionId = execution.getId();

        // 2. Execute via in-process engine
        WorkflowExecutionResult engineResult;
        try {
            engineResult = engine.execute(workflow, executionOrder, userId, executionId);
        } catch (Exception ex) {
            Instant completedAt = Instant.now();
            String sanitizedError = SecretRedactor.redactString("Execution engine failure: " + ex.getMessage());
            execution.markFailed(completedAt, Collections.emptyList(), sanitizedError);
            executionRepository.save(execution);
            throw ex;
        }

        // 3. Transform node execution results with secret redaction for both API return and MongoDB persistence
        WorkflowExecutionResult sanitizedResult = SecretRedactor.sanitize(engineResult);
        List<NodeExecution> nodeExecutions = buildNodeExecutions(sanitizedResult.nodes(), executionOrder);

        // 4. Update and persist final execution state (SUCCESS or FAILED)
        if (sanitizedResult.status() == ExecutionStatus.SUCCESS) {
            execution.markSuccess(sanitizedResult.completedAt(), nodeExecutions);
        } else {
            execution.markFailed(sanitizedResult.completedAt(), nodeExecutions, sanitizedResult.error());
        }
        executionRepository.save(execution);

        // 5. Return sanitized execution result to caller
        return sanitizedResult;
    }

    /**
     * Retrieves an individual execution record strictly scoped to the authenticated user.
     *
     * @param executionId execution ID
     * @param userId authenticated user ID
     * @return full detailed ExecutionResponse
     */
    public ExecutionResponse getExecution(String executionId, String userId) {
        WorkflowExecution execution = executionRepository.findByIdAndUserId(executionId, userId)
                .orElseThrow(() -> new ExecutionNotFoundException("Execution not found with id: " + executionId));

        return ExecutionResponse.fromModel(execution);
    }

    /**
     * Retrieves paginated execution history for a specific workflow owned by the authenticated user.
     *
     * @param workflowId workflow ID
     * @param userId authenticated user ID
     * @param pageable pagination parameters
     * @return lightweight ExecutionSummaryResponse page
     */
    public PageResponse<ExecutionSummaryResponse> getWorkflowExecutions(String workflowId, String userId, Pageable pageable) {
        // Enforce workflow ownership first; hide cross-user existence with 404
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

    /**
     * Retrieves global execution history for the authenticated user, optionally filtered by status.
     *
     * @param userId authenticated user ID
     * @param status optional status filter
     * @param pageable pagination parameters
     * @return lightweight ExecutionSummaryResponse page
     */
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

        // Append skipped downstream nodes if fail-fast halted execution early
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
