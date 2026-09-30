package com.adonis.dto;

import jakarta.validation.constraints.Size;

public record WorkflowTriggerConfigRequest(
        @Size(max = 100, message = "Cron expression must not exceed 100 characters")
        String cronExpression,

        @Size(max = 100, message = "Timezone must not exceed 100 characters")
        String timezone,

        @Size(max = 100, message = "Webhook path must not exceed 100 characters")
        String webhookPath,

        @Size(max = 256, message = "Secret must not exceed 256 characters")
        String secret
) {
}
