package com.adonis.ai;

/**
 * Exception thrown when an AI provider call encounters an error or failure.
 * Preserves HTTP status and retryability status for the execution engine's FailureClassifier.
 */
public class AIProviderException extends RuntimeException {

    private final String provider;
    private final String model;
    private final int statusCode;
    private final boolean retryable;

    public AIProviderException(String message, String provider, String model, int statusCode, boolean retryable) {
        super(message);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public AIProviderException(String message, String provider, String model, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
