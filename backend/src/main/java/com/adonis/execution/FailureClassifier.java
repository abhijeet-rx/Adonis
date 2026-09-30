package com.adonis.execution;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies workflow and node execution failures as retryable or non-retryable.
 *
 * Retryable:
 *   - HTTP 408, 429, 500, 502, 503, 504 (and 5xx server errors)
 *   - Connection timeout, Socket timeout, Connection refused, Temporary network failure
 *
 * Non-retryable:
 *   - Invalid workflow/node configuration, malformed URL, validation errors
 *   - Authentication/authorization failure (HTTP 401, 403)
 *   - HTTP 400, 404, and other 4xx client errors
 *   - Unsupported node types
 *
 * Rule: When in doubt, defaults to NOT retrying to prevent retry storms.
 */
@Component
public class FailureClassifier {

    private static final Set<Integer> RETRYABLE_HTTP_STATUS_CODES = Set.of(
            408, // Request Timeout
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504  // Gateway Timeout
    );

    private static final Pattern RETRYABLE_STATUS_REGEX = Pattern.compile(
            "(?i)\\b(HTTP\\s+)?(408|429|500|502|503|504)\\b"
    );

    private static final Pattern NON_RETRYABLE_STATUS_REGEX = Pattern.compile(
            "(?i)\\b(HTTP\\s+)?(400|401|403|404|405|422)\\b"
    );

    public boolean isRetryable(NodeExecutionResult result) {
        if (result == null || result.status() != ExecutionStatus.FAILED) {
            return false;
        }

        // 1. Check HTTP status code in output if present
        if (result.output() != null && result.output().containsKey("statusCode")) {
            Object sc = result.output().get("statusCode");
            if (sc instanceof Number num) {
                int code = num.intValue();
                if (isRetryableStatusCode(code)) {
                    return true;
                }
                if (isNonRetryableStatusCode(code)) {
                    return false;
                }
            }
        }

        // 2. Check error string
        return isRetryableError(result.error());
    }

    public boolean isRetryableStatusCode(int statusCode) {
        if (RETRYABLE_HTTP_STATUS_CODES.contains(statusCode)) {
            return true;
        }
        // General 5xx server errors are transient
        return statusCode >= 500 && statusCode < 600;
    }

    public boolean isNonRetryableStatusCode(int statusCode) {
        // Any 4xx except 408 and 429
        return statusCode >= 400 && statusCode < 500 && !RETRYABLE_HTTP_STATUS_CODES.contains(statusCode);
    }

    public boolean isRetryableException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof PortUnreachableException
                    || current instanceof UnknownHostException) {
                return true;
            }

            if (current instanceof IOException) {
                String msg = current.getMessage();
                if (msg != null && isTransportErrorMessage(msg)) {
                    return true;
                }
            }

            // Explicit non-retryable exceptions
            if (current instanceof IllegalArgumentException
                    || current instanceof SecurityException
                    || current instanceof NullPointerException
                    || current instanceof UnsupportedOperationException) {
                return false;
            }

            current = current.getCause();
        }

        return isRetryableError(throwable.getMessage());
    }

    public boolean isRetryableError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        String lower = errorMessage.toLowerCase(Locale.ROOT);

        // Check explicit non-retryable phrases first
        if (lower.contains("invalid workflow")
                || lower.contains("invalid node")
                || lower.contains("malformed url")
                || lower.contains("invalid configuration")
                || lower.contains("validation error")
                || lower.contains("schema validation")
                || lower.contains("invalid json")
                || lower.contains("malformed ai output")
                || lower.contains("unsupported node")
                || lower.contains("unsupported ai provider")
                || lower.contains("unsupported http method")
                || lower.contains("missing required")
                || lower.contains("api key is not configured")
                || lower.contains("invalid model")
                || lower.contains("unauthorized")
                || lower.contains("forbidden")
                || lower.contains("not found")
                || lower.contains("bad request")
                || NON_RETRYABLE_STATUS_REGEX.matcher(errorMessage).find()) {
            return false;
        }

        // Check explicit retryable HTTP status regex
        if (RETRYABLE_STATUS_REGEX.matcher(errorMessage).find()) {
            return true;
        }

        // Check retryable transport error messages
        return isTransportErrorMessage(lower);
    }

    private boolean isTransportErrorMessage(String lower) {
        return lower.contains("connection timeout")
                || lower.contains("socket timeout")
                || lower.contains("connection refused")
                || lower.contains("connection reset")
                || lower.contains("timed out")
                || lower.contains("temporary network failure")
                || lower.contains("temporary network")
                || lower.contains("broken pipe")
                || lower.contains("network unreachable")
                || lower.contains("service unavailable")
                || lower.contains("gateway timeout")
                || lower.contains("bad gateway")
                || lower.contains("http connection failed")
                || lower.contains("connection failure")
                || lower.contains("rate limit")
                || lower.contains("too many requests")
                || lower.contains("quota exceeded")
                || lower.contains("resource_exhausted")
                || lower.contains("connectexception");
    }

    public static String getHttpStatusName(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 201 -> "CREATED";
            case 204 -> "NO_CONTENT";
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 408 -> "REQUEST_TIMEOUT";
            case 409 -> "CONFLICT";
            case 422 -> "UNPROCESSABLE_ENTITY";
            case 429 -> "TOO_MANY_REQUESTS";
            case 500 -> "INTERNAL_SERVER_ERROR";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "GATEWAY_TIMEOUT";
            default -> statusCode >= 500 ? "SERVER_ERROR" : (statusCode >= 400 ? "CLIENT_ERROR" : "UNKNOWN");
        };
    }
}
