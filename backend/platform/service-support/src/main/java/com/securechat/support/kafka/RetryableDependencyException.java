package com.securechat.support.kafka;

/**
 * A dependency (service, datastore, broker) is temporarily unavailable. Kafka listeners throwing it
 * are retried indefinitely with capped exponential backoff — the record is never skipped.
 */
public class RetryableDependencyException extends RuntimeException {
    public RetryableDependencyException(String message) {
        super(message);
    }

    public RetryableDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
