package com.acme.messaging;

/**
 * Idempotent-consumer inbox. {@link #firstTime} records that a (listener, messageId) pair has been
 * seen; it returns {@code true} the first time and {@code false} for any duplicate, so a handler can
 * skip already-applied side effects. Call it inside the same transaction as the side effect.
 */
public interface Inbox {

    boolean firstTime(String listener, String messageId);
}
