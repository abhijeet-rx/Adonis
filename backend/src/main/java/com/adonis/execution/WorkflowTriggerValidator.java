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

    private static final java.util.regex.Pattern VALID_WEBHOOK_PATH_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{8,128}$");

    private static final java.util.Set<String> RESERVED_WEBHOOK_PATHS = java.util.Set.of(
            "admin", "api", "health", "metrics", "webhook", "webhooks", "actuator",
            "swagger", "auth", "login", "register", "system", "internal", "occurrences",
            "executions", "workflows", "users", "status"
    );

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
        if (trimmed.length() > 128) {
            throw new WorkflowValidationException("Webhook path must not exceed 128 characters");
        }

        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new WorkflowValidationException("Webhook path must not contain path traversal characters");
        }

        if (!VALID_WEBHOOK_PATH_PATTERN.matcher(trimmed).matches()) {
            throw new WorkflowValidationException("Webhook path contains invalid characters. Only alphanumeric, hyphen, and underscore characters are allowed");
        }

        if (RESERVED_WEBHOOK_PATHS.contains(trimmed.toLowerCase(java.util.Locale.ROOT))) {
            throw new WorkflowValidationException("Webhook path '" + trimmed + "' is a reserved path and cannot be used");
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
