package com.vx.marketplace.application.port;

/** Remembers which Pub/Sub messages were handled, because Pub/Sub delivers at least once. */
public interface ProcessedMessageStore {

    /**
     * Atomically records the message id.
     *
     * @return true if this call recorded it (first delivery), false if it was already recorded (duplicate)
     */
    boolean markProcessed(String messageId);
}
