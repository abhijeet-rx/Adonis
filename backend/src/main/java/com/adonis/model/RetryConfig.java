package com.adonis.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable configuration for node execution retries and backoff.
 * Semantics: maxRetries represents the number of retries AFTER the initial attempt.
 * e.g., maxRetries = 0 means 1 total attempt; maxRetries = 3 means 4 total attempts.
 */
public record RetryConfig(
        boolean enabled,
        int maxRetries,
        long initialBackoffMs,
        double backoffMultiplier,
        long maxBackoffMs
) {
    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_MAX_RETRIES = 0;
    public static final long DEFAULT_INITIAL_BACKOFF_MS = 1000L;
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    public static final long DEFAULT_MAX_BACKOFF_MS = 30000L;

    public static final int MAX_ALLOWED_RETRIES = 10;
    public static final long MAX_ALLOWED_BACKOFF_MS = 60000L;

    public RetryConfig {
        // Safe sanitization and bounds checking
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative: " + maxRetries);
        }
        if (maxRetries > MAX_ALLOWED_RETRIES) {
            throw new IllegalArgumentException("maxRetries cannot exceed " + MAX_ALLOWED_RETRIES + ": " + maxRetries);
        }
        if (initialBackoffMs < 0) {
            throw new IllegalArgumentException("initialBackoffMs cannot be negative: " + initialBackoffMs);
        }
        if (backoffMultiplier <= 0) {
            throw new IllegalArgumentException("backoffMultiplier must be greater than 0: " + backoffMultiplier);
        }
        if (maxBackoffMs < initialBackoffMs) {
            throw new IllegalArgumentException("maxBackoffMs (" + maxBackoffMs + ") cannot be less than initialBackoffMs (" + initialBackoffMs + ")");
        }
        if (maxBackoffMs > MAX_ALLOWED_BACKOFF_MS) {
            throw new IllegalArgumentException("maxBackoffMs cannot exceed " + MAX_ALLOWED_BACKOFF_MS + ": " + maxBackoffMs);
        }
    }

    public static RetryConfig defaultConfig() {
        return new RetryConfig(
                DEFAULT_ENABLED,
                DEFAULT_MAX_RETRIES,
                DEFAULT_INITIAL_BACKOFF_MS,
                DEFAULT_BACKOFF_MULTIPLIER,
                DEFAULT_MAX_BACKOFF_MS
        );
    }

    public static RetryConfig enabled(int maxRetries, long initialBackoffMs, double backoffMultiplier, long maxBackoffMs) {
        return new RetryConfig(true, maxRetries, initialBackoffMs, backoffMultiplier, maxBackoffMs);
    }

    public static RetryConfig enabled(int maxRetries) {
        return new RetryConfig(true, maxRetries, DEFAULT_INITIAL_BACKOFF_MS, DEFAULT_BACKOFF_MULTIPLIER, DEFAULT_MAX_BACKOFF_MS);
    }

    /**
     * Parses RetryConfig from a node data map safely.
     * Supports nested "retry" / "retryConfig" objects or top-level properties.
     */
    public static RetryConfig fromNodeData(Map<String, Object> nodeData) {
        if (nodeData == null || nodeData.isEmpty()) {
            return defaultConfig();
        }

        Map<?, ?> retryMap = null;
        if (nodeData.get("retry") instanceof Map<?, ?> map) {
            retryMap = map;
        } else if (nodeData.get("retryConfig") instanceof Map<?, ?> map) {
            retryMap = map;
        }

        if (retryMap != null) {
            return fromMap(retryMap);
        }

        // Check flat keys
        if (nodeData.containsKey("retryEnabled") || nodeData.containsKey("maxRetries")) {
            return fromMap(nodeData);
        }

        return defaultConfig();
    }

    public static RetryConfig fromMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return defaultConfig();
        }

        boolean enabled = parseBoolean(map.get("enabled"), parseBoolean(map.get("retryEnabled"), DEFAULT_ENABLED));
        int maxRetries = parseInteger(map.get("maxRetries"), DEFAULT_MAX_RETRIES);
        long initialBackoffMs = parseLong(map.get("initialBackoffMs"), parseLong(map.get("retryBackoff"), DEFAULT_INITIAL_BACKOFF_MS));
        double backoffMultiplier = parseDouble(map.get("backoffMultiplier"), parseDouble(map.get("retryBackoffMultiplier"), DEFAULT_BACKOFF_MULTIPLIER));
        long maxBackoffMs = parseLong(map.get("maxBackoffMs"), DEFAULT_MAX_BACKOFF_MS);

        // Sanitize bounds to prevent throwing during parsing from client
        int safeMaxRetries = Math.clamp(maxRetries, 0, MAX_ALLOWED_RETRIES);
        long safeInitialBackoff = Math.clamp(initialBackoffMs, 0L, MAX_ALLOWED_BACKOFF_MS);
        double safeMultiplier = backoffMultiplier > 0 ? backoffMultiplier : DEFAULT_BACKOFF_MULTIPLIER;
        long safeMaxBackoff = Math.clamp(Math.max(maxBackoffMs, safeInitialBackoff), safeInitialBackoff, MAX_ALLOWED_BACKOFF_MS);

        return new RetryConfig(enabled, safeMaxRetries, safeInitialBackoff, safeMultiplier, safeMaxBackoff);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("maxRetries", maxRetries);
        map.put("initialBackoffMs", initialBackoffMs);
        map.put("backoffMultiplier", backoffMultiplier);
        map.put("maxBackoffMs", maxBackoffMs);
        return map;
    }

    private static boolean parseBoolean(Object val, boolean defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    private static int parseInteger(Object val, int defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static long parseLong(Object val, long defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString().trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static double parseDouble(Object val, double defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
