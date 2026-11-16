package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.OutboxMessage;
import com.vx.marketplace.domain.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOutboxStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final InMemoryDatabase db = new InMemoryDatabase();
    private final InMemoryOutboxStore store = new InMemoryOutboxStore(db);
    private final InMemoryUnitOfWork uow = new InMemoryUnitOfWork(db, 3, GRACE);

    private void enqueue(String eventId, Instant createdAt) {
        uow.execute(tx -> {
            tx.enqueue(new OutboxMessage(eventId,
                    new DomainEvent.OfferAccepted("A", "L", "b", "s", 1, createdAt), createdAt));
            return null;
        });
    }

    @Test
    void freshEntriesAreLeftToTheInlinePathUntilGraceElapses() {
        enqueue("e1", T0);

        assertThat(store.claimDue(T0.plusSeconds(29), LEASE, 10)).isEmpty();
        assertThat(store.claimDue(T0.plus(GRACE), LEASE, 10)).extracting(OutboxMessage::eventId).containsExactly("e1");
    }

    @Test
    void claimedEntryIsNotClaimableAgainUntilLeaseExpires() {
        enqueue("e1", T0);
        Instant now = T0.plus(GRACE);

        store.claimDue(now, LEASE, 10);

        assertThat(store.claimDue(now.plusSeconds(59), LEASE, 10)).isEmpty();
        assertThat(store.claimDue(now.plus(LEASE), LEASE, 10)).hasSize(1);
    }

    @Test
    void claimDueReturnsOldestFirstAndRespectsLimit() {
        enqueue("late", T0.plusSeconds(5));
        enqueue("early", T0);

        assertThat(store.claimDue(T0.plusSeconds(100), LEASE, 1)).extracting(OutboxMessage::eventId)
                .containsExactly("early");
    }

    @Test
    void publishedEntryIsNeverDueAndKeepsExpireAt() {
        enqueue("e1", T0);
        Instant expireAt = T0.plus(Duration.ofDays(7));

        store.markPublished("e1", T0.plusSeconds(1), expireAt);
        store.markPublished("e1", T0.plusSeconds(1), expireAt);

        assertThat(store.find("e1").orElseThrow().expireAt()).isEqualTo(expireAt);
        assertThat(store.claimDue(T0.plus(Duration.ofDays(1)), LEASE, 10)).isEmpty();
    }

    @Test
    void unpublishedEntryHasNoExpireAt() {
        enqueue("e1", T0);

        assertThat(store.find("e1").orElseThrow().expireAt()).isNull();
    }

    @Test
    void recordFailureIncrementsAttemptsAndTruncatesError() {
        enqueue("e1", T0);

        store.recordFailure("e1", "x".repeat(900));

        var entry = store.find("e1").orElseThrow();
        assertThat(entry.attempts()).isEqualTo(1);
        assertThat(entry.lastError()).hasSize(500);
    }

    @Test
    void concurrentClaimsNeverReturnTheSameEntryTwice() throws Exception {
        for (int i = 0; i < 20; i++) {
            enqueue("e" + i, T0);
        }
        Instant now = T0.plus(GRACE);
        CountDownLatch start = new CountDownLatch(1);
        List<String> claimed = Collections.synchronizedList(new ArrayList<>());
        var pool = Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                store.claimDue(now, LEASE, 10).forEach(m -> claimed.add(m.eventId()));
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(claimed).hasSize(20).doesNotHaveDuplicates();
    }
}
