package com.adonis.util;

import java.util.*;
import java.util.regex.Pattern;

public final class SecretRedactor {

    public static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEY_SUBSTRINGS = Set.of(
            "authorization",
            "proxy-authorization",
            "token",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "secret",
            "clientsecret",
            "password",
            "passwd",
            "pwd",
            "apikey",
            "api-key",
            "api_key",
            "credential",
            "credentials",
            "privatekey",
            "private_key",
            "cookie",
            "set-cookie"
    );

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)^Bearer\\s+.+$");
    private static final Pattern JWT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+$");

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
    public static Object redactValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            String trimmed = str.trim();
            if (BEARER_PATTERN.matcher(trimmed).matches()) {
                return "Bearer " + REDACTED_VALUE;
            }
            if (trimmed.length() > 20 && JWT_PATTERN.matcher(trimmed).matches()) {
                return REDACTED_VALUE;
            }
            return str;
        }
        return value;
    }

    /**
     * Checks if a key name matches any known sensitive tokens.
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[_-]", "");
        for (String sensitive : SENSITIVE_KEY_SUBSTRINGS) {
            String normalizedSensitive = sensitive.replaceAll("[_-]", "");
            if (normalized.contains(normalizedSensitive)) {
                return true;
            }
        }
        return false;
    }
}
