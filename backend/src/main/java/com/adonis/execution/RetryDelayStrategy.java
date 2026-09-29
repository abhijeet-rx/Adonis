package com.adonis.execution;

/**
 * Strategy interface for delaying execution during retry backoff.
 * Enables zero-sleep fast execution during unit and integration tests.
 */
@FunctionalInterface
public interface RetryDelayStrategy {

    void delay(long millis) throws InterruptedException;

    static RetryDelayStrategy threadSleep() {
        return Thread::sleep;
    }

    static RetryDelayStrategy noOp() {
        return millis -> {};
    }
}
