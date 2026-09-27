package com.adonis.controller;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WorkflowResponse;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.model.WorkflowEdge;
import com.adonis.model.WorkflowNode;
import com.adonis.model.WorkflowStatus;
import com.adonis.repository.UserRepository;
import com.adonis.repository.WorkflowRepository;
import com.adonis.security.JwtService;
import com.adonis.service.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private com.adonis.execution.WorkflowExecutionService executionService;

    @MockBean
    private WorkflowRepository workflowRepository;

    @MockBean
    private UserRepository userRepository;

    private String userAToken;
    private String userBToken;

    @BeforeEach
    void setUp() {
        userAToken = jwtService.generateToken("user-A-id", "usera@example.com", "User A");
        userBToken = jwtService.generateToken("user-B-id", "userb@example.com", "User B");
    }

    // -------------------------------------------------------------
    // CREATE WORKFLOW (POST /api/workflows)
    // -------------------------------------------------------------

    @Test
    void createWorkflow_ShouldReturn201WhenRequestIsValid() throws Exception {
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                "My First Workflow",
                "Test workflow",
                WorkflowStatus.DRAFT,
                List.of(new WorkflowNode("node-1", "http", Map.of())),
                List.of(new WorkflowEdge("edge-1", "node-1", "node-2"))
        );

        Instant now = Instant.now();
        WorkflowResponse response = new WorkflowResponse(
                "wf-101",
                "user-A-id",
                "My First Workflow",
                "Test workflow",
                WorkflowStatus.DRAFT,
                request.nodes(),
                request.edges(),
                now,
                now
        );

        when(workflowService.createWorkflow(eq("user-A-id"), any(CreateWorkflowRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("wf-101"))
                .andExpect(jsonPath("$.userId").value("user-A-id"))
                .andExpect(jsonPath("$.name").value("My First Workflow"))
                .andExpect(jsonPath("$.description").value("Test workflow"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.nodes[0].id").value("node-1"))
                .andExpect(jsonPath("$.edges[0].id").value("edge-1"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        verify(workflowService).createWorkflow(eq("user-A-id"), any(CreateWorkflowRequest.class));
    }

    @Test
    void createWorkflow_ShouldReturn400WhenNameIsBlank() throws Exception {
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                "",
                "Description",
                WorkflowStatus.DRAFT,
                List.of(),
                List.of()
        );

        mockMvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(workflowService, never()).createWorkflow(any(), any());
    }

    @Test
    void createWorkflow_ShouldReturn400WhenNameExceedsMaxLength() throws Exception {
        String longName = "A".repeat(101);
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                longName,
                "Description",
                WorkflowStatus.DRAFT,
                List.of(),
                List.of()
        );

        mockMvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createWorkflow_ShouldReturn401WhenUnauthenticated() throws Exception {
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                "Unauthorized Workflow",
                "Description",
                WorkflowStatus.DRAFT,
                List.of(),
                List.of()
        );

        mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createWorkflow_ShouldReturn400WhenStatusIsInvalid() throws Exception {
        String invalidStatusPayload = """
                {
                    "name": "Invalid Status Workflow",
                    "status": "NONEXISTENT_STATUS"
                }
                """;

        mockMvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidStatusPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createWorkflow_ShouldReturn400WhenJsonIsMalformed() throws Exception {
        String malformedPayload = "{ \"name\": \"Broken JSON\", ";

        mockMvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // -------------------------------------------------------------
    // LIST WORKFLOWS (GET /api/workflows)
    // -------------------------------------------------------------

    @Test
    void listWorkflows_ShouldReturnOnlyCurrentUserWorkflows() throws Exception {
        Instant now = Instant.now();
        WorkflowResponse wfA1 = new WorkflowResponse("wf-1", "user-A-id", "WF A1", "Desc", WorkflowStatus.DRAFT, List.of(), List.of(), now, now);
        WorkflowResponse wfA2 = new WorkflowResponse("wf-2", "user-A-id", "WF A2", "Desc", WorkflowStatus.ACTIVE, List.of(), List.of(), now, now);

        when(workflowService.listWorkflows("user-A-id")).thenReturn(List.of(wfA1, wfA2));

        mockMvc.perform(get("/api/workflows")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("wf-1"))
                .andExpect(jsonPath("$[0].userId").value("user-A-id"))
                .andExpect(jsonPath("$[1].id").value("wf-2"))
                .andExpect(jsonPath("$[1].userId").value("user-A-id"));

        verify(workflowService).listWorkflows("user-A-id");
        verify(workflowService, never()).listWorkflows("user-B-id");
    }

    @Test
    void listWorkflows_ShouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------
    // GET WORKFLOW (GET /api/workflows/{id})
    // -------------------------------------------------------------

    @Test
    void getWorkflow_ShouldReturn200ForOwnWorkflow() throws Exception {
        Instant now = Instant.now();
        WorkflowResponse response = new WorkflowResponse(
                "wf-101",
                "user-A-id",
                "User A Workflow",
                "Test description",
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of(),
                now,
                now
        );

        when(workflowService.getWorkflow("wf-101", "user-A-id")).thenReturn(response);

        mockMvc.perform(get("/api/workflows/wf-101")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-101"))
                .andExpect(jsonPath("$.name").value("User A Workflow"))
                .andExpect(jsonPath("$.userId").value("user-A-id"));
    }

    @Test
    void getWorkflow_ShouldReturn404WhenNonexistent() throws Exception {
        when(workflowService.getWorkflow("nonexistent-wf", "user-A-id"))
                .thenThrow(new WorkflowNotFoundException("Workflow not found with id: nonexistent-wf"));

        mockMvc.perform(get("/api/workflows/nonexistent-wf")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getWorkflow_UserBCannotGetWorkflowOwnedByUserA_Returns404() throws Exception {
        // Cross-user access attempt: User B tries to view User A's workflow
        when(workflowService.getWorkflow("wf-user-A", "user-B-id"))
                .thenThrow(new WorkflowNotFoundException("Workflow not found with id: wf-user-A"));

        mockMvc.perform(get("/api/workflows/wf-user-A")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // -------------------------------------------------------------
    // UPDATE WORKFLOW (PUT /api/workflows/{id})
    // -------------------------------------------------------------

    @Test
    void updateWorkflow_ShouldReturn200WhenValid() throws Exception {
        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "Updated Workflow Name",
                "Updated Description",
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of()
        );

        Instant created = Instant.now().minusSeconds(100);
        Instant updated = Instant.now();
        WorkflowResponse response = new WorkflowResponse(
                "wf-101",
                "user-A-id",
                "Updated Workflow Name",
                "Updated Description",
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of(),
                created,
                updated
        );

        when(workflowService.updateWorkflow(eq("wf-101"), eq("user-A-id"), any(UpdateWorkflowRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/workflows/wf-101")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-101"))
                .andExpect(jsonPath("$.name").value("Updated Workflow Name"))
                .andExpect(jsonPath("$.description").value("Updated Description"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value(created.toString()))
                .andExpect(jsonPath("$.updatedAt").value(updated.toString()));
    }

    @Test
    void updateWorkflow_UserBCannotUpdateWorkflowOwnedByUserA_Returns404() throws Exception {
        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "Hacked Name",
                "Hacked Description",
                WorkflowStatus.ACTIVE,
                List.of(),
                List.of()
        );

        when(workflowService.updateWorkflow(eq("wf-user-A"), eq("user-B-id"), any(UpdateWorkflowRequest.class)))
                .thenThrow(new WorkflowNotFoundException("Workflow not found with id: wf-user-A"));

        mockMvc.perform(put("/api/workflows/wf-user-A")
                        .header("Authorization", "Bearer " + userBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateWorkflow_ShouldReturn400WhenNameIsBlank() throws Exception {
        UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                "",
                "Description",
                WorkflowStatus.DRAFT,
                List.of(),
                List.of()
        );

        mockMvc.perform(put("/api/workflows/wf-101")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // -------------------------------------------------------------
    // DELETE WORKFLOW (DELETE /api/workflows/{id})
    // -------------------------------------------------------------

    @Test
    void deleteWorkflow_ShouldReturn204WhenOwnWorkflow() throws Exception {
        doNothing().when(workflowService).deleteWorkflow("wf-101", "user-A-id");

        mockMvc.perform(delete("/api/workflows/wf-101")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNoContent());

        verify(workflowService).deleteWorkflow("wf-101", "user-A-id");
    }

    @Test
    void deleteWorkflow_UserBCannotDeleteWorkflowOwnedByUserA_Returns404() throws Exception {
        doThrow(new WorkflowNotFoundException("Workflow not found with id: wf-user-A"))
                .when(workflowService).deleteWorkflow("wf-user-A", "user-B-id");

        mockMvc.perform(delete("/api/workflows/wf-user-A")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(workflowService).deleteWorkflow("wf-user-A", "user-B-id");
    }

    @Test
    void deleteWorkflow_ShouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(delete("/api/workflows/wf-101"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------
    // EXECUTE WORKFLOW (POST /api/workflows/{id}/execute)
    // -------------------------------------------------------------

    @Test
    void executeWorkflow_ShouldReturn200AndResultWhenAuthorized() throws Exception {
        Instant now = Instant.now();
        com.adonis.execution.WorkflowExecutionResult result = com.adonis.execution.WorkflowExecutionResult.success(
                "exec-123",
                "wf-101",
                now,
                now,
                List.of(
                        com.adonis.execution.NodeExecutionResult.success(
                                "node-1",
                                "trigger",
                                now,
                                now,
                                Map.of("trigger", "manual")
                        )
                )
        );

        when(executionService.executeWorkflow("wf-101", "user-A-id")).thenReturn(result);

        mockMvc.perform(post("/api/workflows/wf-101/execute")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value("exec-123"))
                .andExpect(jsonPath("$.workflowId").value("wf-101"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("node-1"))
                .andExpect(jsonPath("$.nodes[0].status").value("SUCCESS"));

        verify(executionService).executeWorkflow("wf-101", "user-A-id");
    }

    @Test
    void executeWorkflow_ShouldReturn404WhenWorkflowNotFoundOrNotOwned() throws Exception {
        when(executionService.executeWorkflow("wf-unowned", "user-B-id"))
                .thenThrow(new WorkflowNotFoundException("Workflow not found with id: wf-unowned"));

        mockMvc.perform(post("/api/workflows/wf-unowned/execute")
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(executionService).executeWorkflow("wf-unowned", "user-B-id");
    }

    @Test
    void executeWorkflow_ShouldReturn400WhenValidationFails() throws Exception {
        when(executionService.executeWorkflow("wf-invalid", "user-A-id"))
                .thenThrow(new com.adonis.exception.WorkflowValidationException("Workflow execution graph contains a cycle"));

        mockMvc.perform(post("/api/workflows/wf-invalid/execute")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Workflow execution graph contains a cycle"));

        verify(executionService).executeWorkflow("wf-invalid", "user-A-id");
    }

    @Test
    void executeWorkflow_ShouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/workflows/wf-101/execute"))
                .andExpect(status().isUnauthorized());

        verify(executionService, never()).executeWorkflow(any(), any());
    }
}
