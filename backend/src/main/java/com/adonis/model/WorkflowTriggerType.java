package com.adonis.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enumeration of supported workflow trigger mechanisms.
 */
public enum WorkflowTriggerType {
    MANUAL("MANUAL"),
    SCHEDULE("SCHEDULE"),
    WEBHOOK("WEBHOOK");

    private final String value;

    WorkflowTriggerType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static WorkflowTriggerType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MANUAL;
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "SCHEDULE", "CRON", "SCHEDULED" -> SCHEDULE;
            case "WEBHOOK" -> WEBHOOK;
            default -> MANUAL;
        };
    }
}
