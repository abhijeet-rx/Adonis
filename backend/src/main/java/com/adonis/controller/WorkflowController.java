package com.adonis.controller;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WorkflowResponse;
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

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
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

    private UserPrincipal getAuthenticatedPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return principal;
    }
}
