package com.adonis.execution;

import com.adonis.dto.ExecutionResponse;
import com.adonis.dto.ExecutionSummaryResponse;
import com.adonis.dto.PageResponse;
import com.adonis.exception.ExecutionNotFoundException;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.NodeExecution;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowExecution;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.util.SecretRedactor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class WorkflowExecutionService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionValidator validator;
    private final WorkflowExecutionEngine engine;
    private final WorkflowExecutionRepository executionRepository;

    public WorkflowExecutionService(
            WorkflowRepository workflowRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine,
            WorkflowExecutionRepository executionRepository) {
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "WorkflowRepository must not be null");
        this.validator = Objects.requireNonNull(validator, "WorkflowExecutionValidator must not be null");
        this.engine = Objects.requireNonNull(engine, "WorkflowExecutionEngine must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "WorkflowExecutionRepository must not be null");
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
                        sanitizedError
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
