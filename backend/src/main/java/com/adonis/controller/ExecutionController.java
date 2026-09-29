package com.adonis.controller;

import com.adonis.dto.ExecutionResponse;
import com.adonis.dto.ExecutionSummaryResponse;
import com.adonis.dto.PageResponse;
import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.security.UserPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final WorkflowExecutionService executionService;

    public ExecutionController(WorkflowExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ExecutionResponse> getExecution(
            @PathVariable String executionId,
            Authentication authentication) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);
        ExecutionResponse response = executionService.getExecution(executionId, principal.id());
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<ExecutionSummaryResponse>> listExecutions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ExecutionStatus status,
            Authentication authentication) {
        UserPrincipal principal = getAuthenticatedPrincipal(authentication);

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "startedAt"));

        PageResponse<ExecutionSummaryResponse> response = executionService.getUserExecutions(principal.id(), status, pageable);
        return ResponseEntity.ok(response);
    }

    private UserPrincipal getAuthenticatedPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return principal;
    }
}
