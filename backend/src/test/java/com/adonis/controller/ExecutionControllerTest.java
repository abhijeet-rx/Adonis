package com.adonis.controller;

import com.adonis.dto.ExecutionResponse;
import com.adonis.dto.ExecutionSummaryResponse;
import com.adonis.dto.NodeExecutionResponse;
import com.adonis.dto.PageResponse;
import com.adonis.exception.ExecutionNotFoundException;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.execution.ExecutionStatus;
import com.adonis.execution.WorkflowExecutionService;
import com.adonis.repository.UserRepository;
import com.adonis.repository.WorkflowExecutionRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private WorkflowExecutionService executionService;

    @MockBean
    private WorkflowRepository workflowRepository;

    @MockBean
    private WorkflowExecutionRepository executionRepository;

    @MockBean
    private UserRepository userRepository;

    private String userAToken;
    private String userBToken;

    @BeforeEach
    void setUp() {
        userAToken = jwtService.generateToken("user-A-id", "usera@example.com", "User A");
        userBToken = jwtService.generateToken("user-B-id", "userb@example.com", "User B");
    }

    @Test
    void getExecution_AuthorizedOwner_Returns200AndFullDetail() throws Exception {
        Instant now = Instant.now();
        ExecutionResponse response = new ExecutionResponse(
                "exec-101",
                "wf-101",
                ExecutionStatus.SUCCESS,
                "manual",
                now,
                now.plusMillis(120),
                120L,
                List.of(
                        new NodeExecutionResponse("t1", "trigger", ExecutionStatus.SUCCESS, now, now.plusMillis(20), 20L, Map.of(), Map.of(), null)
                ),
                null
        );

        when(executionService.getExecution("exec-101", "user-A-id")).thenReturn(response);

        mockMvc.perform(get("/api/executions/exec-101")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("exec-101"))
                .andExpect(jsonPath("$.executionId").value("exec-101"))
                .andExpect(jsonPath("$.workflowId").value("wf-101"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.durationMs").value(120))
                .andExpect(jsonPath("$.nodeExecutions[0].nodeId").value("t1"));

        verify(executionService).getExecution("exec-101", "user-A-id");
    }

    @Test
    void getExecution_OwnershipMismatchOrNotFound_Returns404() throws Exception {
        when(executionService.getExecution("exec-other", "user-B-id"))
                .thenThrow(new ExecutionNotFoundException("Execution not found with id: exec-other"));

        mockMvc.perform(get("/api/executions/exec-other")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(executionService).getExecution("exec-other", "user-B-id");
    }

    @Test
    void getExecution_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/executions/exec-101"))
                .andExpect(status().isUnauthorized());

        verify(executionService, never()).getExecution(any(), any());
    }

    @Test
    void listExecutions_Authorized_Returns200AndPaginatedSummary() throws Exception {
        Instant now = Instant.now();
        ExecutionSummaryResponse item = new ExecutionSummaryResponse(
                "exec-101",
                "wf-101",
                ExecutionStatus.SUCCESS,
                "manual",
                now,
                now.plusMillis(50),
                50L,
                null
        );
        PageResponse<ExecutionSummaryResponse> page = PageResponse.of(List.of(item), 0, 20, 1L, 1);

        when(executionService.getUserExecutions(eq("user-A-id"), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/executions?page=0&size=20")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("exec-101"))
                .andExpect(jsonPath("$.content[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(executionService).getUserExecutions(eq("user-A-id"), any(), any(Pageable.class));
    }

    @Test
    void getWorkflowExecutions_AuthorizedOwner_Returns200AndPaginatedSummary() throws Exception {
        Instant now = Instant.now();
        ExecutionSummaryResponse item = new ExecutionSummaryResponse(
                "exec-1",
                "wf-101",
                ExecutionStatus.SUCCESS,
                "manual",
                now,
                now.plusMillis(100),
                100L,
                null
        );
        PageResponse<ExecutionSummaryResponse> page = PageResponse.of(List.of(item), 0, 20, 1L, 1);

        when(executionService.getWorkflowExecutions(eq("wf-101"), eq("user-A-id"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/workflows/wf-101/executions?page=0&size=20")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("exec-1"))
                .andExpect(jsonPath("$.content[0].workflowId").value("wf-101"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(executionService).getWorkflowExecutions(eq("wf-101"), eq("user-A-id"), any(Pageable.class));
    }

    @Test
    void getWorkflowExecutions_UnownedWorkflow_Returns404() throws Exception {
        when(executionService.getWorkflowExecutions(eq("wf-unowned"), eq("user-B-id"), any(Pageable.class)))
                .thenThrow(new WorkflowNotFoundException("Workflow not found with id: wf-unowned"));

        mockMvc.perform(get("/api/workflows/wf-unowned/executions")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(executionService).getWorkflowExecutions(eq("wf-unowned"), eq("user-B-id"), any(Pageable.class));
    }
}
