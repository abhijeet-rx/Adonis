package com.adonis.util;

import com.adonis.execution.NodeExecutionResult;
import com.adonis.execution.WorkflowExecutionResult;
import com.adonis.model.NodeExecutionAttempt;

import java.util.*;
import java.util.regex.Pattern;

public final class SecretRedactor {

    public static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEY_SUBSTRINGS = Set.of(
            "authorization",
            "proxyauthorization",
            "token",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "authtoken",
            "sessiontoken",
            "secret",
            "clientsecret",
            "password",
            "passwd",
            "pwd",
            "apikey",
            "credential",
            "credentials",
            "privatekey",
            "cookie",
            "setcookie",
            "bearer",
            "authentication"
    );

    // PEM Private Key pattern
    private static final Pattern PEM_PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"
    );

    // URL with embedded credentials: https?://user:password@host
    private static final Pattern URL_CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)(https?://)([^:/?#@\\s]*):([^/?#@\\s]+)@"
    );

    // Authorization / Proxy-Authorization / Authentication headers in text
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)\\b(Authorization|Proxy-Authorization|Authentication)\\s*:\\s*(?!\\[REDACTED\\])[^\\r\\n,;]+"
    );

    // Standalone Bearer header value
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)^Bearer\\s+.+$");

    // Embedded Bearer token in text
    private static final Pattern EMBEDDED_BEARER_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+(?!\\[REDACTED\\])[a-zA-Z0-9_\\-\\.~+/]+=*"
    );

    // Embedded key=value or key: value for sensitive keys (e.g., apiKey, secretToken, password)
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b([a-zA-Z0-9_-]*(?:api[-_]?key|password|passwd|pwd|secret|token|credential|private[-_]?key)[a-zA-Z0-9_-]*)\\s*([:=]\\s*)(?!\\[REDACTED\\])([^\\r\\n\\s,;&\"'\\[\\]{}>]+)"
    );

    // Full-string JWT check
    private static final Pattern JWT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+$");

    // Embedded JWT token (standard 3-segment base64url starting with eyJ)
    private static final Pattern EMBEDDED_JWT_PATTERN = Pattern.compile(
            "\\beyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\b"
    );

    private SecretRedactor() {
    }

    /**
     * Recursively sanitizes a Map, redacting sensitive keys or values.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactMap(Map<String, ?> input) {
        if (input == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key == null) {
                continue;
            }

            if (isSensitiveKey(key)) {
                sanitized.put(key, REDACTED_VALUE);
            } else if (value instanceof Map<?, ?> nestedMap) {
                sanitized.put(key, redactMap((Map<String, ?>) nestedMap));
            } else if (value instanceof Collection<?> collection) {
                sanitized.put(key, redactCollection(collection));
            } else {
                sanitized.put(key, redactValue(value));
            }
        }
        return sanitized;
    }

    /**
     * Recursively sanitizes a Collection.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> redactCollection(Collection<?> collection) {
        if (collection == null) {
            return Collections.emptyList();
        }
        List<Object> sanitized = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item instanceof Map<?, ?> map) {
                sanitized.add(redactMap((Map<String, ?>) map));
            } else if (item instanceof Collection<?> nestedCol) {
                sanitized.add(redactCollection(nestedCol));
            } else {
                sanitized.add(redactValue(item));
            }
        }
        return sanitized;
    }

    /**
     * Inspects primitive or string values for sensitive tokens.
     */
    @SuppressWarnings("unchecked")
    public static Object redactValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return redactString(str);
        }
        if (value instanceof Map<?, ?> map) {
            return redactMap((Map<String, ?>) map);
        }
        if (value instanceof Collection<?> collection) {
            return redactCollection(collection);
        }
        return value;
    }

    /**
     * Deeply sanitizes arbitrary strings, error messages, and exception texts.
     */
    public static String redactString(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (BEARER_PATTERN.matcher(trimmed).matches()) {
            return "Bearer " + REDACTED_VALUE;
        }
        if (trimmed.length() > 20 && JWT_PATTERN.matcher(trimmed).matches()) {
            return REDACTED_VALUE;
        }

        String result = input;
        result = PEM_PRIVATE_KEY_PATTERN.matcher(result).replaceAll(REDACTED_VALUE);
        result = URL_CREDENTIAL_PATTERN.matcher(result).replaceAll("$1$2:" + REDACTED_VALUE + "@");
        result = AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1: " + REDACTED_VALUE);
        result = EMBEDDED_BEARER_PATTERN.matcher(result).replaceAll("Bearer " + REDACTED_VALUE);
        result = KEY_VALUE_SECRET_PATTERN.matcher(result).replaceAll("$1$2" + REDACTED_VALUE);
        result = EMBEDDED_JWT_PATTERN.matcher(result).replaceAll(REDACTED_VALUE);

        return result;
    }

    /**
     * Checks if a key name matches any known sensitive tokens.
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[_-]", "");
        if (normalized.equals("auth") || normalized.endsWith("auth") || normalized.contains("basicauth") || normalized.contains("authtoken")) {
            return true;
        }
        for (String sensitive : SENSITIVE_KEY_SUBSTRINGS) {
            if (normalized.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a full WorkflowExecutionResult for both API response and database persistence.
     */
    public static WorkflowExecutionResult sanitize(WorkflowExecutionResult raw) {
        if (raw == null) {
            return null;
        }
        List<NodeExecutionResult> sanitizedNodes = raw.nodes() != null
                ? raw.nodes().stream().map(SecretRedactor::sanitizeNodeResult).toList()
                : Collections.emptyList();
        String sanitizedError = redactString(raw.error());
        return new WorkflowExecutionResult(
                raw.executionId(),
                raw.workflowId(),
                raw.status(),
                raw.startedAt(),
                raw.completedAt(),
                raw.durationMs(),
                sanitizedNodes,
                sanitizedError
        );
    }

    /**
     * Sanitizes an individual NodeExecutionAttempt.
     */
    public static NodeExecutionAttempt sanitizeAttempt(NodeExecutionAttempt raw) {
        if (raw == null) {
            return null;
        }
        return new NodeExecutionAttempt(
                raw.getAttemptNumber(),
                raw.getStatus(),
                raw.getStartedAt(),
                raw.getCompletedAt(),
                raw.getDurationMs(),
                redactMap(raw.getInput()),
                redactMap(raw.getOutput()),
                redactString(raw.getError())
        );
    }

    /**
     * Sanitizes an individual NodeExecutionResult.
     */
    public static NodeExecutionResult sanitizeNodeResult(NodeExecutionResult raw) {
        if (raw == null) {
            return null;
        }
        List<NodeExecutionAttempt> sanitizedAttempts = raw.attempts() != null
                ? raw.attempts().stream().map(SecretRedactor::sanitizeAttempt).toList()
                : Collections.emptyList();
        return new NodeExecutionResult(
                raw.nodeId(),
                raw.nodeType(),
                raw.status(),
                raw.startedAt(),
                raw.completedAt(),
                raw.durationMs(),
                redactMap(raw.input()),
                redactMap(raw.output()),
                redactString(raw.error()),
                raw.retryCount(),
                sanitizedAttempts
        );
    }
}
