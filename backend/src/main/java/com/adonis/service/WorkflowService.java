package com.adonis.service;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WebhookRegenerateResponse;
import com.adonis.dto.WorkflowResponse;
import com.adonis.dto.WorkflowTriggerConfigRequest;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.execution.WorkflowTriggerValidator;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerConfig;
import com.adonis.model.WorkflowTriggerType;
import com.adonis.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowTriggerValidator triggerValidator;

    public WorkflowService(WorkflowRepository workflowRepository) {
        this(workflowRepository, new WorkflowTriggerValidator());
    }

    @Autowired
    public WorkflowService(WorkflowRepository workflowRepository, WorkflowTriggerValidator triggerValidator) {
        this.workflowRepository = workflowRepository;
        this.triggerValidator = triggerValidator != null ? triggerValidator : new WorkflowTriggerValidator();
    }

    public WorkflowResponse createWorkflow(String userId, CreateWorkflowRequest request) {
        WorkflowStatus status = request.status() != null ? request.status() : WorkflowStatus.DRAFT;
        WorkflowTriggerType triggerType = request.triggerType() != null ? request.triggerType() : WorkflowTriggerType.MANUAL;

        WorkflowTriggerConfig triggerConfig = buildTriggerConfigFromRequest(triggerType, request.triggerConfig(), null);

        Workflow workflow = Workflow.create(
                userId,
                request.name(),
                request.description(),
                status,
                request.nodes() != null ? request.nodes() : new ArrayList<>(),
                request.edges() != null ? request.edges() : new ArrayList<>(),
                triggerType,
                triggerConfig
        );

        triggerValidator.validateTriggerConfig(workflow);

        Workflow saved = workflowRepository.save(workflow);
        return WorkflowResponse.fromWorkflow(saved);
    }

    public List<WorkflowResponse> listWorkflows(String userId) {
        return workflowRepository.findByUserId(userId).stream()
                .map(WorkflowResponse::fromWorkflow)
                .toList();
    }

    public WorkflowResponse getWorkflow(String id, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + id));
        return WorkflowResponse.fromWorkflow(workflow);
    }

    public WorkflowResponse updateWorkflow(String id, String userId, UpdateWorkflowRequest request) {
        Workflow workflow = workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + id));

        workflow.setName(request.name().trim());
        workflow.setDescription(request.description() != null ? request.description().trim() : null);
        if (request.status() != null) {
            workflow.setStatus(request.status());
        }
        if (request.nodes() != null) {
            workflow.setNodes(request.nodes());
        }
        if (request.edges() != null) {
            workflow.setEdges(request.edges());
        }

        if (request.triggerType() != null) {
            workflow.setTriggerType(request.triggerType());
        }

        if (request.triggerConfig() != null || request.triggerType() != null) {
            WorkflowTriggerConfig existingConfig = workflow.getTriggerConfig();
            WorkflowTriggerConfig updatedConfig = buildTriggerConfigFromRequest(
                    workflow.getTriggerType(),
                    request.triggerConfig(),
                    existingConfig
            );
            workflow.setTriggerConfig(updatedConfig);
        }

        triggerValidator.validateTriggerConfig(workflow);

        workflow.setUpdatedAt(Instant.now());

        Workflow saved = workflowRepository.save(workflow);
        return WorkflowResponse.fromWorkflow(saved);
    }

    public WebhookRegenerateResponse regenerateWebhook(String id, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + id));

        WorkflowTriggerConfig config = workflow.getTriggerConfig();
        if (config == null) {
            config = new WorkflowTriggerConfig();
            workflow.setTriggerConfig(config);
        }

        if (config.getWebhookPath() == null || config.getWebhookPath().isBlank()) {
            config.setWebhookPath(WorkflowTriggerConfig.generateWebhookPath());
        }

        String rawSecret = WorkflowTriggerConfig.generateWebhookSecret();
        config.setSecretHash(WorkflowTriggerConfig.hashSecret(rawSecret));
        config.setHasSecret(true);

        workflow.setUpdatedAt(Instant.now());
        workflowRepository.save(workflow);

        String webhookUrl = "/api/webhooks/" + config.getWebhookPath();
        return new WebhookRegenerateResponse(workflow.getId(), config.getWebhookPath(), webhookUrl, rawSecret);
    }

    public void deleteWorkflow(String id, String userId) {
        long deletedCount = workflowRepository.deleteByIdAndUserId(id, userId);
        if (deletedCount == 0) {
            throw new WorkflowNotFoundException("Workflow not found with id: " + id);
        }
    }

    private WorkflowTriggerConfig buildTriggerConfigFromRequest(
            WorkflowTriggerType triggerType,
            WorkflowTriggerConfigRequest requestConfig,
            WorkflowTriggerConfig existingConfig) {

        WorkflowTriggerConfig config = existingConfig != null ? existingConfig : new WorkflowTriggerConfig();

        if (triggerType == WorkflowTriggerType.SCHEDULE) {
            if (requestConfig != null) {
                if (requestConfig.cronExpression() != null) {
                    config.setCronExpression(requestConfig.cronExpression().trim());
                }
                if (requestConfig.timezone() != null) {
                    config.setTimezone(requestConfig.timezone().trim());
                }
            }
        } else if (triggerType == WorkflowTriggerType.WEBHOOK) {
            if (requestConfig != null) {
                if (requestConfig.webhookPath() != null && !requestConfig.webhookPath().isBlank()) {
                    config.setWebhookPath(requestConfig.webhookPath().trim());
                }
                if (requestConfig.secret() != null && !requestConfig.secret().isBlank()) {
                    config.setSecretHash(WorkflowTriggerConfig.hashSecret(requestConfig.secret()));
                    config.setHasSecret(true);
                }
            }
            if (config.getWebhookPath() == null || config.getWebhookPath().isBlank()) {
                config.setWebhookPath(WorkflowTriggerConfig.generateWebhookPath());
            }
        }

        return config;
    }
}
