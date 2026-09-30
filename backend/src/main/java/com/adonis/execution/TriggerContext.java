package com.adonis.execution;

import com.adonis.model.WorkflowTriggerType;

import java.util.Collections;
import java.util.Map;

/**
 * Execution trigger representation encapsulating trigger type and trigger payload data.
 * Decouples the execution engine from webhooks, schedulers, and HTTP controllers.
 */
public record TriggerContext(
        WorkflowTriggerType triggerType,
        Map<String, Object> payload,
        Map<String, Object> metadata
) {
    public TriggerContext {
        if (triggerType == null) {
            triggerType = WorkflowTriggerType.MANUAL;
        }
        payload = payload != null ? Collections.unmodifiableMap(payload) : Collections.emptyMap();
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    public static TriggerContext manual() {
        return new TriggerContext(WorkflowTriggerType.MANUAL, Map.of(), Map.of());
    }

    public static TriggerContext manual(String userId) {
        return new TriggerContext(WorkflowTriggerType.MANUAL, Map.of("userId", userId != null ? userId : ""), Map.of());
    }

    public static TriggerContext schedule(String cronExpression, String timezone, String fireTime) {
        return new TriggerContext(
                WorkflowTriggerType.SCHEDULE,
                Map.of(
                        "type", "SCHEDULE",
                        "cronExpression", cronExpression != null ? cronExpression : "",
                        "timezone", timezone != null ? timezone : "UTC",
                        "scheduledFireTime", fireTime != null ? fireTime : ""
                ),
                Map.of()
        );
    }

    public static TriggerContext webhook(Map<String, Object> webhookPayload) {
        return new TriggerContext(WorkflowTriggerType.WEBHOOK, webhookPayload != null ? webhookPayload : Map.of(), Map.of());
    }
}
