package com.adonis.service;

import com.adonis.dto.CreateWorkflowRequest;
import com.adonis.dto.UpdateWorkflowRequest;
import com.adonis.dto.WebhookRegenerateResponse;
import com.adonis.dto.WorkflowResponse;
import com.adonis.dto.WorkflowTriggerConfigRequest;
import com.adonis.exception.WorkflowNotFoundException;
import com.adonis.exception.WorkflowValidationException;
import com.adonis.execution.WorkflowTriggerValidator;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowStatus;
import com.adonis.model.WorkflowTriggerConfig;
import com.adonis.model.WorkflowTriggerType;
import com.adonis.repository.ScheduledOccurrenceRepository;
import com.adonis.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowTriggerValidator triggerValidator;
    private final ScheduledOccurrenceRepository scheduledOccurrenceRepository;

    public WorkflowService(WorkflowRepository workflowRepository) {
        this(workflowRepository, new WorkflowTriggerValidator(), null);
    }

    public WorkflowService(WorkflowRepository workflowRepository, WorkflowTriggerValidator triggerValidator) {
        this(workflowRepository, triggerValidator, null);
    }

    @Autowired
    public WorkflowService(
            WorkflowRepository workflowRepository,
            WorkflowTriggerValidator triggerValidator,
            @Autowired(required = false) ScheduledOccurrenceRepository scheduledOccurrenceRepository) {
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "WorkflowRepository must not be null");
        this.triggerValidator = triggerValidator != null ? triggerValidator : new WorkflowTriggerValidator();
        this.scheduledOccurrenceRepository = scheduledOccurrenceRepository;
    }

    public WorkflowResponse createWorkflow(String userId, CreateWorkflowRequest request) {
        WorkflowStatus status = request.status() != null ? request.status() : WorkflowStatus.DRAFT;
        WorkflowTriggerType triggerType = request.triggerType() != null ? request.triggerType() : WorkflowTriggerType.MANUAL;

        WorkflowTriggerConfig triggerConfig = buildTriggerConfigFromRequest(triggerType, request.triggerConfig(), null, null);

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

        if (workflow.getTriggerType() == WorkflowTriggerType.WEBHOOK) {
            validateWebhookPathUniqueness(workflow.getTriggerConfig().getWebhookPath(), null);
        }

        Workflow saved = saveWorkflowSafely(workflow);
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

        WorkflowTriggerType previousTriggerType = workflow.getTriggerType();
        if (request.triggerType() != null) {
            workflow.setTriggerType(request.triggerType());
        }

        if (request.triggerConfig() != null || request.triggerType() != null) {
            WorkflowTriggerConfig existingConfig = workflow.getTriggerConfig();
            WorkflowTriggerConfig updatedConfig = buildTriggerConfigFromRequest(
                    workflow.getTriggerType(),
                    request.triggerConfig(),
                    existingConfig,
                    previousTriggerType
            );
            workflow.setTriggerConfig(updatedConfig);
        }

        triggerValidator.validateTriggerConfig(workflow);

        if (workflow.getTriggerType() == WorkflowTriggerType.WEBHOOK) {
            validateWebhookPathUniqueness(workflow.getTriggerConfig().getWebhookPath(), workflow.getId());
        }

        workflow.setUpdatedAt(Instant.now());

        Workflow saved = saveWorkflowSafely(workflow);
        return WorkflowResponse.fromWorkflow(saved);
    }

    public WebhookRegenerateResponse regenerateWebhook(String id, String userId) {
        Workflow workflow = workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + id));

        if (workflow.getTriggerType() != WorkflowTriggerType.WEBHOOK) {
            throw new WorkflowValidationException("Cannot regenerate webhook for workflow with trigger type: " + workflow.getTriggerType());
        }

        WorkflowTriggerConfig config = workflow.getTriggerConfig();
        if (config == null) {
            config = new WorkflowTriggerConfig();
            workflow.setTriggerConfig(config);
        }

        String rawSecret = WorkflowTriggerConfig.generateWebhookSecret();
        config.setSecretHash(WorkflowTriggerConfig.hashSecret(rawSecret));
        config.setHasSecret(true);

        if (config.getWebhookPath() == null || config.getWebhookPath().isBlank()) {
            config.setWebhookPath(WorkflowTriggerConfig.generateWebhookPath());
        }

        workflow.setUpdatedAt(Instant.now());

        Workflow saved = saveWorkflowSafely(workflow);

        String webhookUrl = "/api/webhooks/" + saved.getTriggerConfig().getWebhookPath();
        return new WebhookRegenerateResponse(saved.getId(), saved.getTriggerConfig().getWebhookPath(), webhookUrl, rawSecret);
    }

    public void deleteWorkflow(String id, String userId) {
        // 1. Verify existence and ownership
        workflowRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow not found with id: " + id));

        // 2. Clean up durable scheduled occurrences to avoid leaving orphans
        if (scheduledOccurrenceRepository != null) {
            try {
                long deletedOccurrences = scheduledOccurrenceRepository.deleteByWorkflowId(id);
                log.info("Cleaned {} scheduled occurrences for deleted workflow {}", deletedOccurrences, id);
            } catch (Exception ex) {
                log.error("Failed to clean scheduled occurrences for workflow {}: {}", id, ex.getMessage(), ex);
            }
        }

        // 3. Delete the workflow document
        long deletedCount = workflowRepository.deleteByIdAndUserId(id, userId);
        if (deletedCount == 0) {
            throw new WorkflowNotFoundException("Workflow not found with id: " + id);
        }
    }

    private void validateWebhookPathUniqueness(String webhookPath, String excludeWorkflowId) {
        if (webhookPath == null || webhookPath.isBlank()) {
            return;
        }
        Optional<Workflow> existing = workflowRepository.findByTriggerConfigWebhookPath(webhookPath.trim());
        if (existing.isPresent()) {
            String existingId = existing.get().getId();
            if (excludeWorkflowId == null || !existingId.equals(excludeWorkflowId)) {
                throw new WorkflowValidationException("Webhook path is already in use: " + webhookPath.trim());
            }
        }
    }

    private Workflow saveWorkflowSafely(Workflow workflow) {
        try {
            return workflowRepository.save(workflow);
        } catch (DuplicateKeyException ex) {
            String path = (workflow.getTriggerConfig() != null) ? workflow.getTriggerConfig().getWebhookPath() : "unknown";
            log.warn("MongoDB unique index violation on webhook path '{}' for workflow {}: {}", path, workflow.getId(), ex.getMessage());
            throw new WorkflowValidationException("Webhook path is already in use: " + path);
        }
    }

    private WorkflowTriggerConfig buildTriggerConfigFromRequest(
            WorkflowTriggerType newTriggerType,
            WorkflowTriggerConfigRequest requestConfig,
            WorkflowTriggerConfig existingConfig,
            WorkflowTriggerType previousTriggerType) {

        WorkflowTriggerConfig config = existingConfig != null ? existingConfig : new WorkflowTriggerConfig();

        if (newTriggerType == WorkflowTriggerType.MANUAL) {
            // Fix #3: Clear all schedule and webhook configuration
            config.clearAll();
            return config;
        }

        if (newTriggerType == WorkflowTriggerType.SCHEDULE) {
            // Fix #3: Clear webhook configuration
            config.clearWebhookConfig();

            boolean scheduleChanged = (previousTriggerType != null && previousTriggerType != WorkflowTriggerType.SCHEDULE);

            if (requestConfig != null) {
                if (requestConfig.cronExpression() != null) {
                    String newCron = requestConfig.cronExpression().trim();
                    if (!Objects.equals(newCron, config.getCronExpression())) {
                        scheduleChanged = true;
                        config.setCronExpression(newCron);
                    }
                }
                if (requestConfig.timezone() != null) {
                    String newTz = requestConfig.timezone().trim();
                    if (!Objects.equals(newTz, config.getTimezone())) {
                        scheduleChanged = true;
                        config.setTimezone(newTz);
                    }
                }
            }

            // Fix #4: Schedule changes must invalidate stored next fire time
            if (scheduleChanged) {
                config.resetScheduleState();
            }
            return config;
        }

        if (newTriggerType == WorkflowTriggerType.WEBHOOK) {
            // Fix #3: Clear schedule configuration
            config.clearScheduleConfig();

            if (requestConfig != null) {
                if (requestConfig.webhookPath() != null && !requestConfig.webhookPath().isBlank()) {
                    config.setWebhookPath(requestConfig.webhookPath().trim());
                }
                if (requestConfig.secret() != null && !requestConfig.secret().isBlank()) {
                    config.setSecretHash(WorkflowTriggerConfig.hashSecret(requestConfig.secret()));
                    config.setHasSecret(true);
                }
            }

            // Fix #9 & Fix #10: Ensure capability path is present (generates 64 hex characters if missing)
            if (config.getWebhookPath() == null || config.getWebhookPath().isBlank()) {
                config.setWebhookPath(WorkflowTriggerConfig.generateWebhookPath());
            }
            return config;
        }

        return config;
    }
}
