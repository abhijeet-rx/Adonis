package com.adonis.queue;

/**
 * Exception thrown when enqueuing a workflow execution to the message broker fails.
 * Handled gracefully by the API layer without leaking infrastructure internals.
 */
public class QueueSubmissionException extends RuntimeException {

    public QueueSubmissionException(String message) {
        super(message);
    }

    public QueueSubmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
