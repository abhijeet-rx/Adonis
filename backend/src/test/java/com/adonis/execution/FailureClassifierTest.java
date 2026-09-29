package com.adonis.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504, 507, 508, 599})
    void isRetryableStatusCode_ReturnsTrueForTransientStatuses(int statusCode) {
        assertTrue(classifier.isRetryableStatusCode(statusCode),
                "Status " + statusCode + " should be classified as retryable");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 422})
    void isRetryableStatusCode_ReturnsFalseForClientErrors(int statusCode) {
        assertFalse(classifier.isRetryableStatusCode(statusCode),
                "Status " + statusCode + " should be classified as non-retryable");
        assertTrue(classifier.isNonRetryableStatusCode(statusCode),
                "Status " + statusCode + " should be classified as non-retryable status code");
    }

    @Test
    void isRetryableException_TimeoutAndConnectionExceptionsAreRetryable() {
        assertTrue(classifier.isRetryableException(new HttpTimeoutException("Request timed out")));
        assertTrue(classifier.isRetryableException(new SocketTimeoutException("Read timed out")));
        assertTrue(classifier.isRetryableException(new ConnectException("Connection refused")));
        assertTrue(classifier.isRetryableException(new IOException("Connection reset by peer")));
        assertTrue(classifier.isRetryableException(new IOException("Temporary network failure: host unreachable")));
        assertTrue(classifier.isRetryableException(new IOException("Broken pipe")));
    }

    @Test
    void isRetryableException_NestedCauseIsDetected() {
        RuntimeException wrapped = new RuntimeException("Outer failure", new ConnectException("Connection refused"));
        assertTrue(classifier.isRetryableException(wrapped));
    }

    @Test
    void isRetryableException_ValidationAndConfigurationExceptionsAreNonRetryable() {
        assertFalse(classifier.isRetryableException(new IllegalArgumentException("Invalid node configuration")));
        assertFalse(classifier.isRetryableException(new SecurityException("Authentication failure")));
        assertFalse(classifier.isRetryableException(new NullPointerException("Missing field")));
        assertFalse(classifier.isRetryableException(new UnsupportedOperationException("Unsupported node type")));
    }

    @Test
    void isRetryableError_ErrorMessagesClassifiedCorrectly() {
        // Retryable
        assertTrue(classifier.isRetryableError("HTTP 503 Service Unavailable"));
        assertTrue(classifier.isRetryableError("HTTP 502 Bad Gateway"));
        assertTrue(classifier.isRetryableError("HTTP 500 Internal Server Error"));
        assertTrue(classifier.isRetryableError("HTTP 408 Request Timeout"));
        assertTrue(classifier.isRetryableError("HTTP 429 Too Many Requests"));
        assertTrue(classifier.isRetryableError("Connection refused by remote host"));
        assertTrue(classifier.isRetryableError("HTTP request timed out after 10s"));
        assertTrue(classifier.isRetryableError("Temporary network failure"));

        // Non-retryable
        assertFalse(classifier.isRetryableError("HTTP 400 Bad Request"));
        assertFalse(classifier.isRetryableError("HTTP 401 Unauthorized"));
        assertFalse(classifier.isRetryableError("HTTP 403 Forbidden"));
        assertFalse(classifier.isRetryableError("HTTP 404 Not Found"));
        assertFalse(classifier.isRetryableError("Malformed URL: not a valid uri"));
        assertFalse(classifier.isRetryableError("Invalid workflow configuration: cycle detected"));
        assertFalse(classifier.isRetryableError("Validation error: missing required 'url'"));
        assertFalse(classifier.isRetryableError("Unsupported HTTP method: PATCH"));
    }

    @Test
    void isRetryable_ClassifiesNodeExecutionResultProperly() {
        Instant now = Instant.now();

        // 1. Success node is never retryable
        NodeExecutionResult successResult = NodeExecutionResult.success("n1", "http", now, now, Map.of("statusCode", 200));
        assertFalse(classifier.isRetryable(successResult));

        // 2. Failure with retryable status code in output
        NodeExecutionResult retryableHttpResult = NodeExecutionResult.failure("n1", "http", now, now,
                Map.of(), Map.of("statusCode", 503), "HTTP 503 SERVICE_UNAVAILABLE");
        assertTrue(classifier.isRetryable(retryableHttpResult));

        // 3. Failure with non-retryable status code in output
        NodeExecutionResult nonRetryableHttpResult = NodeExecutionResult.failure("n1", "http", now, now,
                Map.of(), Map.of("statusCode", 401), "HTTP 401 UNAUTHORIZED");
        assertFalse(classifier.isRetryable(nonRetryableHttpResult));

        // 4. Failure with transport error in message
        NodeExecutionResult connRefusedResult = NodeExecutionResult.failure("n1", "http", now, now,
                Map.of(), "HTTP connection failed: Connection refused");
        assertTrue(classifier.isRetryable(connRefusedResult));
    }
}
