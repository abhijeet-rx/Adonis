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

    @Test
    void redactString_UrlCredentials_Redacted() {
        String error = "Failed connecting to https://admin:mySecretPassword123@api.internal.com:8443/v1/resource";
        String redacted = SecretRedactor.redactString(error);

        assertFalse(redacted.contains("mySecretPassword123"));
        assertEquals("Failed connecting to https://admin:[REDACTED]@api.internal.com:8443/v1/resource", redacted);
    }

    @Test
    void redactString_EmbeddedKeyValueSecrets_Redacted() {
        String error = "Error in auth: apiKey=abc-123-secret and password: my-secret-pass and token=jwt-token-xyz";
        String redacted = SecretRedactor.redactString(error);

        assertFalse(redacted.contains("abc-123-secret"));
        assertFalse(redacted.contains("my-secret-pass"));
        assertFalse(redacted.contains("jwt-token-xyz"));
        assertTrue(redacted.contains("apiKey=[REDACTED]"));
        assertTrue(redacted.contains("password: [REDACTED]"));
        assertTrue(redacted.contains("token=[REDACTED]"));
    }

    @Test
    void redactString_EmbeddedBearerAndAuthHeader_Redacted() {
        String error = "Server returned 401 with Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0In0.xyz";
        String redacted = SecretRedactor.redactString(error);

        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(redacted.contains("Authorization: [REDACTED]"));
    }

    @Test
    void redactString_PemPrivateKey_Redacted() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----";
        String redacted = SecretRedactor.redactString(pem);

        assertEquals("[REDACTED]", redacted);
    }

    @Test
    void isSensitiveKey_MatchesSensitiveKeyNames() {
        assertTrue(SecretRedactor.isSensitiveKey("authorization"));
        assertTrue(SecretRedactor.isSensitiveKey("Authorization"));
        assertTrue(SecretRedactor.isSensitiveKey("apiKey"));
        assertTrue(SecretRedactor.isSensitiveKey("x-api-key"));
        assertTrue(SecretRedactor.isSensitiveKey("client_secret"));
        assertTrue(SecretRedactor.isSensitiveKey("password"));
        assertTrue(SecretRedactor.isSensitiveKey("pwd"));
        assertTrue(SecretRedactor.isSensitiveKey("accessToken"));
        assertTrue(SecretRedactor.isSensitiveKey("refresh_token"));
        assertTrue(SecretRedactor.isSensitiveKey("auth"));
        assertTrue(SecretRedactor.isSensitiveKey("authToken"));

        // Safe keys
        assertFalse(SecretRedactor.isSensitiveKey("author"));
        assertFalse(SecretRedactor.isSensitiveKey("authority"));
        assertFalse(SecretRedactor.isSensitiveKey("url"));
        assertFalse(SecretRedactor.isSensitiveKey("status"));
        assertFalse(SecretRedactor.isSensitiveKey("name"));
    }
}
