package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.OutboxMessage;
import com.vx.marketplace.application.port.OutboxStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class InMemoryOutboxStore implements OutboxStore {

    static final int MAX_ERROR_LENGTH = 500;

    /** Persisted state of one outbox entry. */
    public record Entry(OutboxMessage message, Instant relayAfter, Instant publishedAt, Instant expireAt,
                        int attempts, String lastError) { }

    private final InMemoryDatabase db;

    public InMemoryOutboxStore(InMemoryDatabase db) {
        this.db = db;
    }

    @Override
    public List<OutboxMessage> claimDue(Instant now, Duration lease, int limit) {
        db.lock.lock();
        try {
            List<Entry> due = db.outbox.values().stream()
                    .filter(e -> e.publishedAt() == null && !e.relayAfter().isAfter(now))
                    .sorted(Comparator.comparing((Entry e) -> e.message().createdAt()))
                    .limit(limit)
                    .toList();
            due.forEach(e -> db.outbox.put(e.message().eventId(), new Entry(e.message(), now.plus(lease),
                    null, null, e.attempts(), e.lastError())));
            return due.stream().map(Entry::message).toList();
        } finally {
            db.lock.unlock();
        }
    }

    @Override
    public void markPublished(String eventId, Instant publishedAt, Instant expireAt) {
        update(eventId, e -> new Entry(e.message(), e.relayAfter(), publishedAt, expireAt, e.attempts(), e.lastError()));
    }

    @Override
    public void recordFailure(String eventId, String error) {
        String truncated = error == null || error.length() <= MAX_ERROR_LENGTH
                ? error : error.substring(0, MAX_ERROR_LENGTH);
        update(eventId, e -> new Entry(e.message(), e.relayAfter(), e.publishedAt(), e.expireAt(),
                e.attempts() + 1, truncated));
    }

    /** Test/inspection helper. */
    public Optional<Entry> find(String eventId) {
        return Optional.ofNullable(db.outbox.get(eventId));
    }

    public List<Entry> all() {
        return List.copyOf(db.outbox.values());
    }

    private void update(String eventId, java.util.function.UnaryOperator<Entry> change) {
        db.lock.lock();
        try {
            db.outbox.computeIfPresent(eventId, (id, e) -> change.apply(e));
        } finally {
            db.lock.unlock();
        }
    }
}
