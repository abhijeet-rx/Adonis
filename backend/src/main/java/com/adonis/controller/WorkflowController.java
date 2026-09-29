package com.adonis.controller;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WorkflowResponse;
import com.adonis.execution.WorkflowExecutionResult;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.security.UserPrincipal;
import com.adonis.service.WorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowExecutionService executionService;

    public WorkflowController(WorkflowService workflowService, WorkflowExecutionService executionService) {
        this.workflowService = workflowService;
        this.executionService = executionService;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> createWorkflow(
            Authentication authentication,
            @Valid @RequestBody CreateWorkflowRequest request) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        WorkflowResponse response = workflowService.createWorkflow(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<WorkflowResponse>> listWorkflows(Authentication authentication) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        List<WorkflowResponse> response = workflowService.listWorkflows(principal.id());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowResponse> getWorkflow(
            @PathVariable String id,
            Authentication authentication) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        WorkflowResponse response = workflowService.getWorkflow(id, principal.id());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkflowResponse> updateWorkflow(
            @PathVariable String id,
            Authentication authentication,
            @Valid @RequestBody UpdateWorkflowRequest request) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        WorkflowResponse response = workflowService.updateWorkflow(id, principal.id(), request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(
            @PathVariable String id,
            Authentication authentication) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        workflowService.deleteWorkflow(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<WorkflowExecutionResult> executeWorkflow(
            @PathVariable String id,
            Authentication authentication) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        WorkflowExecutionResult result = executionService.executeWorkflow(id, principal.id());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<com.adonis.dto.PageResponse<com.adonis.dto.ExecutionSummaryResponse>> getWorkflowExecutions(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                safePage,
                safeSize,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "startedAt")
        );
        com.adonis.dto.PageResponse<com.adonis.dto.ExecutionSummaryResponse> response =
                executionService.getWorkflowExecutions(id, principal.id(), pageable);
        return ResponseEntity.ok(response);
    }

    private UserPrincipal getAuthenticatedPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return principal;
    }
}
