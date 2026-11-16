package com.vx.marketplace.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface OutboxStore {
    /** Atomically claims up to {@code limit} unpublished entries with relayAfter <= now, pushing relayAfter to now+lease. */
    List<OutboxMessage> claimDue(Instant now, Duration lease, int limit);

    /** Idempotent. */
    void markPublished(String eventId, Instant publishedAt, Instant expireAt);

    /** Increments attempts and records the (truncated) error. */
    void recordFailure(String eventId, String error);
}
