package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.ListingRepository;
import com.vx.marketplace.application.port.OfferRepository;
import com.vx.marketplace.application.port.OutboxMessage;
import com.vx.marketplace.application.port.UnitOfWork;
import com.vx.marketplace.application.port.UnitOfWorkContractTest;
import com.vx.marketplace.domain.DomainEvent;
import com.vx.marketplace.domain.ListingStatus;
import com.vx.marketplace.domain.OfferStatus;
import org.junit.jupiter.api.Test;

import com.vx.marketplace.domain.BusinessRuleException;
import com.vx.marketplace.domain.Offer;
import com.vx.marketplace.domain.NotFoundException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryUnitOfWorkTest extends UnitOfWorkContractTest {

    private static final Duration GRACE = Duration.ofSeconds(30);

    private final InMemoryDatabase db = new InMemoryDatabase();
    private final InMemoryListingRepository listings = new InMemoryListingRepository(db);
    private final InMemoryOfferRepository offers = new InMemoryOfferRepository(db);
    private final InMemoryOutboxStore outbox = new InMemoryOutboxStore(db);
    private InMemoryUnitOfWork current;

    @Override
    protected UnitOfWork uow(int maxAttempts) {
        current = new InMemoryUnitOfWork(db, maxAttempts, GRACE);
        return current;
    }

    @Override
    protected void beforeCommit(Consumer<Integer> hook) {
        current.beforeCommit(hook);
    }

    @Override
    protected ListingRepository listings() {
        return listings;
    }

    @Override
    protected OfferRepository offers() {
        return offers;
    }

    private OutboxMessage message(String eventId) {
        return new OutboxMessage(eventId,
                new DomainEvent.OfferAccepted("A", "L", "buyer-1", "seller", 900, NOW), NOW);
    }

    @Test
    void failedCommitLeavesNoTraceAndNextAttemptSucceeds() {
        UnitOfWork uow = uow(3);
        current.failNextCommit(new IllegalStateException("boom"));
        java.util.function.Function<com.vx.marketplace.application.port.TransactionContext, Object> accept = tx -> {
            tx.findListing("L");
            tx.save(listing.markSold());
            tx.save(offerA.accept());
            tx.enqueue(message("e1"));
            return null;
        };

        assertThatThrownBy(() -> uow.execute(accept)).hasMessage("boom");

        assertThat(listings.findById("L")).map(l -> l.status()).contains(ListingStatus.ACTIVE);
        assertThat(offers.findById("A")).map(o -> o.status()).contains(OfferStatus.PENDING);
        assertThat(outbox.all()).isEmpty();

        uow.execute(accept);
        assertThat(outbox.all()).hasSize(1);
    }

    @Test
    void enqueuedMessageIsCommittedWithStateAndDueAfterGrace() {
        uow(3).execute(tx -> {
            tx.findListing("L");
            tx.save(listing.markSold());
            tx.enqueue(message("e1"));
            return null;
        });

        var entry = outbox.find("e1").orElseThrow();
        assertThat(entry.relayAfter()).isEqualTo(NOW.plus(GRACE));
        assertThat(entry.publishedAt()).isNull();
    }

    @Test
    void persistentContentionLeavesNoOutboxEntry() {
        uow(2);
        current.beforeCommit(attempt -> listings.save(new com.vx.marketplace.domain.Listing("L", "seller", "v" + attempt,
                1000, "USD", ListingStatus.ACTIVE, NOW)));
        assertThatThrownBy(() -> current.execute(tx -> {
            tx.findListing("L");
            tx.enqueue(message("e1"));
            return null;
        })).isInstanceOf(com.vx.marketplace.application.port.ConcurrencyConflictException.class);

        assertThat(outbox.all()).isEmpty();
    }

    @Test
    void enqueueIsRolledBackWithStateWhenFunctionThrows() {
        UnitOfWork uow = uow(3);

        assertThatThrownBy(() -> uow.execute(tx -> {
            tx.findListing("L");
            tx.save(listing.markSold());
            tx.enqueue(message("e1"));
            throw new NotFoundException("boom");
        })).isInstanceOf(NotFoundException.class);

        assertThat(outbox.all()).isEmpty();
        assertThat(listings.findById("L")).contains(listing);
    }

    @Test
    void enqueueingAnEventIdTwiceKeepsTheFirstEntryUntouched() {
        UnitOfWork uow = uow(3);
        uow.execute(tx -> { tx.enqueue(message("e1")); return null; });
        outbox.markPublished("e1", NOW, NOW.plusSeconds(60));

        uow.execute(tx -> { tx.enqueue(message("e1")); return null; });

        assertThat(outbox.all()).hasSize(1);
        assertThat(outbox.find("e1").orElseThrow().publishedAt()).isEqualTo(NOW);
    }

    @Test
    void concurrentAcceptsOfDifferentOffersLeaveExactlyOneWinnerAndOneEvent() throws Exception {
        int threads = 8;
        for (int i = 0; i < threads; i++) {
            offers.save(Offer.place("O" + i, "L", "buyer-" + i, 100 + i, "k" + i, NOW));
        }
        UnitOfWork uow = new InMemoryUnitOfWork(db, 50, GRACE);

        List<Object> outcomes = runConcurrently(threads, i -> uow.execute(tx -> accept(tx, "O" + i)));

        assertThat(outcomes.stream().filter(o -> !(o instanceof Throwable))).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o instanceof Throwable))
                .allMatch(o -> o instanceof BusinessRuleException);
        assertThat(listings.findById("L")).map(l -> l.status()).contains(ListingStatus.SOLD);
        assertThat(offers.findByListingId("L").stream().filter(o -> o.status() == OfferStatus.ACCEPTED)).hasSize(1);
        assertThat(offers.findByListingId("L").stream().filter(o -> o.status() == OfferStatus.PENDING)).isEmpty();
        assertThat(outbox.all()).hasSize(1);
    }

    @Test
    void concurrentAcceptsOfTheSameOfferAllSucceedAndRecordOneEvent() throws Exception {
        int threads = 8;
        UnitOfWork uow = new InMemoryUnitOfWork(db, 50, GRACE);

        List<Object> outcomes = runConcurrently(threads, i -> uow.execute(tx -> accept(tx, "A")));

        assertThat(outcomes).noneMatch(o -> o instanceof Throwable);
        assertThat(outbox.all()).hasSize(1);
        assertThat(offers.findById("A")).map(Offer::status).contains(OfferStatus.ACCEPTED);
    }

    /** Mirrors what the rewired service will do inside the transaction. */
    private Object accept(com.vx.marketplace.application.port.TransactionContext tx, String offerId) {
        var l = tx.findListing("L").orElseThrow();
        var all = tx.findOffersByListing("L");
        var chosen = all.stream().filter(o -> o.id().equals(offerId)).findFirst().orElseThrow();
        var out = l.acceptOffer(chosen, all);
        if (!out.alreadyAccepted()) {
            tx.save(out.sold());
            tx.save(out.accepted());
            out.rejected().forEach(tx::save);
            tx.enqueue(message("evt-" + offerId));
        }
        return out.accepted();
    }

    private List<Object> runConcurrently(int threads, java.util.function.IntFunction<Object> task) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return task.apply(n);
                } catch (RuntimeException e) {
                    return e;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (var f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }
}
