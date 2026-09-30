package com.adonis.dto;

import com.adonis.model.WorkflowTriggerConfig;

import java.time.Instant;

public record WorkflowTriggerConfigResponse(
        String cronExpression,
        String timezone,
        Instant lastScheduledFireTime,
        Instant nextFireTime,
        String webhookPath,
        boolean hasSecret,
        String secret
) {
    public static WorkflowTriggerConfigResponse fromModel(WorkflowTriggerConfig config) {
        if (config == null) {
            return new WorkflowTriggerConfigResponse(null, "UTC", null, null, null, false, null);
        }
        String maskedSecret = config.isHasSecret() ? "********" : null;
        return new WorkflowTriggerConfigResponse(
                config.getCronExpression(),
                config.getTimezone(),
                config.getLastScheduledFireTime(),
                config.getNextFireTime(),
                config.getWebhookPath(),
                config.isHasSecret(),
                maskedSecret
        );
    }
}
