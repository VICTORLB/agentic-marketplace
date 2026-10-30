package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;

/** An event recorded in the same transaction as the state change, to be relayed to the broker later. */
public record OutboxMessage(String eventId, DomainEvent event, Instant createdAt) {
    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
