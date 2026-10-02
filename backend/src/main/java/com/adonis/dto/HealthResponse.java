package com.adonis.dto;

import java.time.Instant;

public record HealthResponse(
        String status,
        String service,
        String version,
        String commit,
        Instant timestamp
) {
    public static HealthResponse ok(String service, String version, String commit) {
        return new HealthResponse("UP", service, version, commit, Instant.now());
    }

    public static HealthResponse ok(String service, String version) {
        return new HealthResponse("UP", service, version, "unknown", Instant.now());
    }
}
