package com.adonis.dto;

public record WebhookRegenerateResponse(
        String workflowId,
        String webhookPath,
        String webhookUrl,
        String secret
) {
}
