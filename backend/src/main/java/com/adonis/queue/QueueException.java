package com.adonis.queue;

/**
 * Base runtime exception for queue-related operational errors (enqueue, poll, serialization).
 */
public class QueueException extends RuntimeException {

    public QueueException(String message) {
        super(message);
    }

    public QueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
