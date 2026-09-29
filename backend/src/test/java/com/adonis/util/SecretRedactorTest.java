package com.adonis.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {

    @Test
    void redactMap_SensitiveKeys_ReplacedWithRedacted() {
        Map<String, Object> input = Map.of(
                "authorization", "Bearer my-secret-jwt-token",
                "apiKey", "12345-abcde",
                "client_secret", "supersecret",
                "password", "secretPassword123",
                "safeField", "public-value"
        );

        Map<String, Object> result = SecretRedactor.redactMap(input);

        assertEquals("[REDACTED]", result.get("authorization"));
        assertEquals("[REDACTED]", result.get("apiKey"));
        assertEquals("[REDACTED]", result.get("client_secret"));
        assertEquals("[REDACTED]", result.get("password"));
        assertEquals("public-value", result.get("safeField"));
    }

    @Test
    void redactMap_BearerHeaderValue_Redacted() {
        Map<String, Object> input = Map.of(
                "customHeader", "Bearer abc.def.ghi",
                "normalText", "Just normal text"
        );

        Map<String, Object> result = SecretRedactor.redactMap(input);

        assertEquals("Bearer [REDACTED]", result.get("customHeader"));
        assertEquals("Just normal text", result.get("normalText"));
    }

    @Test
    void redactMap_NestedStructures_RedactedRecursively() {
        Map<String, Object> nested = Map.of(
                "headers", Map.of(
                        "Authorization", "Bearer top-secret",
                        "Accept", "application/json"
                ),
                "list", List.of(
                        Map.of("password", "p1"),
                        Map.of("token", "t1"),
                        "regular-string"
                )
        );

        Map<String, Object> result = SecretRedactor.redactMap(nested);

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) result.get("headers");
        assertEquals("[REDACTED]", headers.get("Authorization"));
        assertEquals("application/json", headers.get("Accept"));

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result.get("list");
        @SuppressWarnings("unchecked")
        Map<String, Object> item0 = (Map<String, Object>) list.get(0);
        assertEquals("[REDACTED]", item0.get("password"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item1 = (Map<String, Object>) list.get(1);
        assertEquals("[REDACTED]", item1.get("token"));
        assertEquals("regular-string", list.get(2));
    }

    @Test
    void redactMap_NullAndEmpty_ReturnsEmptyMap() {
        assertTrue(SecretRedactor.redactMap(null).isEmpty());
        assertTrue(SecretRedactor.redactMap(Map.of()).isEmpty());
    }
}
