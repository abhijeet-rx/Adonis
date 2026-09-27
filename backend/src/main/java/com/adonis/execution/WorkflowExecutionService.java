package com.adonis.execution;

import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowNode;
import com.adonis.repository.WorkflowRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowExecutionService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionValidator validator;
    private final WorkflowExecutionEngine engine;

    public WorkflowExecutionService(
            WorkflowRepository workflowRepository,
            WorkflowExecutionValidator validator,
            WorkflowExecutionEngine engine) {
        this.workflowRepository = workflowRepository;
        this.validator = validator;
        this.engine = engine;
    }

    /**
     * Loads the workflow verifying ownership, validates its graph structure,
     * and synchronously executes each node in topological order.
     *
     * @param workflowId the workflow ID
     * @param userId the authenticated user ID
     * @return WorkflowExecutionResult
     */
    public WorkflowExecutionResult executeWorkflow(String workflowId, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + workflowId));

        List<WorkflowNode> executionOrder = validator.validateAndOrder(workflow);
        return engine.execute(workflow, executionOrder, userId);
    }
}
