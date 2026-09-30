package com.adonis.queue;

/**
 * Exception thrown when enqueuing a workflow execution to the message broker fails.
 * Handled gracefully by the API layer without leaking infrastructure internals.
 */
public class QueueSubmissionException extends RuntimeException {

    private final String executionId;

    public QueueSubmissionException(String message) {
        this(null, message, null);
    }

    public QueueSubmissionException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public QueueSubmissionException(String executionId, String message) {
        this(executionId, message, null);
    }

    public QueueSubmissionException(String executionId, String message, Throwable cause) {
        super(message, cause);
        this.executionId = executionId;
    }

    public String getExecutionId() {
        return executionId;
    }
}
