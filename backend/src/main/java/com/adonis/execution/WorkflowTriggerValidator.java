package com.adonis.execution;

import com.adonis.exception.WorkflowValidationException;
import com.adonis.model.Workflow;
import com.adonis.model.WorkflowTriggerConfig;
import com.adonis.model.WorkflowTriggerType;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Validates workflow trigger configuration according to Phase 8 requirements.
 */
@Component
public class WorkflowTriggerValidator {

    /**
     * Validates trigger configuration for a workflow based on its triggerType.
     *
     * @param workflow the workflow to validate
     * @throws WorkflowValidationException if the trigger configuration is invalid
     */
    public void validateTriggerConfig(Workflow workflow) {
        if (workflow == null) {
            throw new WorkflowValidationException("Workflow cannot be null");
        }

        WorkflowTriggerType type = workflow.getTriggerType();
        if (type == null || type == WorkflowTriggerType.MANUAL) {
            return; // Manual workflows require no trigger configuration
        }

        WorkflowTriggerConfig config = workflow.getTriggerConfig();
        if (config == null) {
            throw new WorkflowValidationException("Trigger configuration is required for trigger type: " + type);
        }

        if (type == WorkflowTriggerType.SCHEDULE) {
            validateScheduleConfig(config);
        } else if (type == WorkflowTriggerType.WEBHOOK) {
            validateWebhookConfig(config);
        }
    }

    public void validateScheduleConfig(WorkflowTriggerConfig config) {
        if (config == null) {
            throw new WorkflowValidationException("Schedule trigger configuration must not be null");
        }

        String cron = config.getCronExpression();
        if (cron == null || cron.trim().isEmpty()) {
            throw new WorkflowValidationException("Cron expression is required for SCHEDULE workflow trigger");
        }

        parseAndValidateCron(cron);
        parseAndValidateZoneId(config.getTimezone());
    }

    public void validateWebhookConfig(WorkflowTriggerConfig config) {
        if (config == null) {
            throw new WorkflowValidationException("Webhook trigger configuration must not be null");
        }

        String path = config.getWebhookPath();
        if (path == null || path.trim().isEmpty()) {
            throw new WorkflowValidationException("Webhook path is required for WEBHOOK workflow trigger");
        }

        String trimmed = path.trim();
        if (trimmed.length() < 8) {
            throw new WorkflowValidationException("Webhook path must be at least 8 characters for security");
        }
    }

    /**
     * Parses and validates a cron expression. Supports standard 6-field Spring cron expressions
     * as well as standard 5-field cron expressions (normalized with leading '0' for seconds).
     *
     * @param cron the cron expression string
     * @return parsed Spring CronExpression
     * @throws WorkflowValidationException if the cron expression is invalid
     */
    public static CronExpression parseAndValidateCron(String cron) {
        if (cron == null || cron.trim().isEmpty()) {
            throw new WorkflowValidationException("Cron expression must not be blank");
        }
        String normalized = cron.trim();
        String[] parts = normalized.split("\\s+");
        if (parts.length == 5) {
            // Standard 5-field cron (min hour dom mon dow): prepend 0 for seconds
            normalized = "0 " + normalized;
        } else if (parts.length != 6) {
            throw new WorkflowValidationException(
                    "Invalid cron expression: '" + cron + "'. Expected 5 or 6 fields, found " + parts.length
            );
        }

        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException ex) {
            throw new WorkflowValidationException("Invalid cron expression '" + cron + "': " + ex.getMessage());
        }
    }

    /**
     * Parses and validates a timezone string using ZoneId. Defaults to UTC if null or blank.
     *
     * @param timezone the timezone identifier
     * @return resolved ZoneId
     * @throws WorkflowValidationException if the timezone is unknown or malformed
     */
    public static ZoneId parseAndValidateZoneId(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException ex) {
            throw new WorkflowValidationException("Invalid timezone: '" + timezone + "'. " + ex.getMessage());
        }
    }
}
