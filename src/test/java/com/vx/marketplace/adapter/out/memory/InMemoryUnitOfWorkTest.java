package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.ListingRepository;
import com.vx.marketplace.application.port.OfferRepository;
import com.vx.marketplace.application.port.OutboxMessage;
import com.vx.marketplace.application.port.UnitOfWork;
import com.vx.marketplace.application.port.UnitOfWorkContractTest;
import com.vx.marketplace.domain.DomainEvent;
import com.vx.marketplace.domain.BusinessRuleException;
import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.ListingStatus;
import com.vx.marketplace.domain.Offer;
import com.vx.marketplace.domain.OfferStatus;
import org.junit.jupiter.api.Test;

import com.vx.marketplace.domain.NotFoundException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    void rerunsFunctionWhenReadDocumentChangesBeforeCommit() {
        UnitOfWork uow = uow(3);
        current.beforeCommit(attempt -> {
            if (attempt == 1) {
                listings.save(listing.markSold());
            }
        });
        List<ListingStatus> seen = new ArrayList<>();

        uow.execute(tx -> {
            seen.add(tx.findListing("L").orElseThrow().status());
            return null;
        });

        assertThat(seen).containsExactly(ListingStatus.ACTIVE, ListingStatus.SOLD);
    }

    @Test
    void detectsPhantomOfferAddedToQueriedListing() {
        UnitOfWork uow = uow(3);
        current.beforeCommit(attempt -> {
            if (attempt == 1) {
                offers.save(Offer.place("B", "L", "buyer-2", 800, "k2", NOW));
            }
        });
        List<Integer> sizes = new ArrayList<>();

        uow.execute(tx -> {
            sizes.add(tx.findOffersByListing("L").size());
            return null;
        });

        assertThat(sizes).containsExactly(1, 2);
    }

    @Test
    void throwsConcurrencyConflictAfterMaxAttempts() {
        UnitOfWork uow = uow(3);
        current.beforeCommit(attempt -> listings.save(new Listing("L", "seller", "Bike v" + attempt, 1000, "USD",
                ListingStatus.ACTIVE, NOW)));
        List<Integer> runs = new ArrayList<>();

        assertThatThrownBy(() -> uow.execute(tx -> {
            runs.add(1);
            tx.findListing("L");
            tx.save(offerA.accept());
            return null;
        })).isInstanceOf(com.vx.marketplace.application.port.ConcurrencyConflictException.class)
                .hasMessageContaining("listing L");

        assertThat(runs).hasSize(3);
        assertThat(offers.findById("A")).contains(offerA);
    }

    @Test
    void rerunsInsteadOfFailingWhenCompetingAcceptCommitsBetweenTwoReads() {
        UnitOfWork uow = uow(3);
        List<Integer> runs = new ArrayList<>();

        boolean alreadyAccepted = uow.execute(tx -> {
            runs.add(1);
            Listing l = tx.findListing("L").orElseThrow();
            if (runs.size() == 1) {
                accept(new InMemoryUnitOfWork(db), "A"); // competitor commits between the two reads
            }
            return acceptIn(tx, l, "A");
        });

        assertThat(runs).hasSize(2);
        assertThat(alreadyAccepted).isTrue();
    }

    @Test
    void constructorRejectsInvalidSettings() {
        assertThatThrownBy(() -> new InMemoryUnitOfWork(db, 0, GRACE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryUnitOfWork(db, 1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentAcceptsOfTheSameOfferAllSucceedAndRecordOneEvent() throws Exception {
        for (int rep = 0; rep < 300; rep++) {
            Fixture f = new Fixture(1);
            List<Boolean> results = f.race(8, i -> "A");

            assertThat(results).as("rep %d", rep).containsOnly(true);
            assertThat(f.outbox.all()).as("rep %d", rep).hasSize(1);
            assertThat(f.ls.findById("L")).map(Listing::status).contains(ListingStatus.SOLD);
            assertThat(f.offers.findById("A")).map(Offer::status).contains(OfferStatus.ACCEPTED);
        }
    }

    @Test
    void concurrentAcceptsOfDifferentOffersHaveExactlyOneWinner() throws Exception {
        for (int rep = 0; rep < 100; rep++) {
            Fixture f = new Fixture(8);
            List<Boolean> results = f.race(8, i -> "O" + i);

            assertThat(results).as("rep %d", rep).containsOnlyOnce(true);
            assertThat(f.offers.findByListingId("L")).filteredOn(o -> o.status() == OfferStatus.ACCEPTED).hasSize(1);
            assertThat(f.offers.findByListingId("L")).noneMatch(o -> o.status() == OfferStatus.PENDING);
            assertThat(f.ls.findById("L")).map(Listing::status).contains(ListingStatus.SOLD);
            assertThat(f.outbox.all()).as("rep %d", rep).hasSize(1);
        }
    }

    /** Domain accept inside a transaction; returns whether it was an idempotent retry. */
    private boolean acceptIn(com.vx.marketplace.application.port.TransactionContext tx, Listing l, String offerId) {
        Offer chosen = tx.findOffer(offerId).orElseThrow();
        var outcome = l.acceptOffer(chosen, tx.findOffersByListing(l.id()));
        if (!outcome.alreadyAccepted()) {
            tx.save(outcome.sold());
            tx.save(outcome.accepted());
            outcome.rejected().forEach(tx::save);
            tx.enqueue(message(java.util.UUID.randomUUID().toString()));
        }
        return outcome.alreadyAccepted();
    }

    private void accept(InMemoryUnitOfWork uow, String offerId) {
        uow.execute(tx -> acceptIn(tx, tx.findListing("L").orElseThrow(), offerId));
    }

    /** Fresh database with listing L and pending offers O0..O(n-1) (plus A from the base fixture is unused). */
    private final class Fixture {
        final InMemoryDatabase d = new InMemoryDatabase();
        final InMemoryListingRepository ls = new InMemoryListingRepository(d);
        final InMemoryOfferRepository offers = new InMemoryOfferRepository(d);
        final InMemoryOutboxStore outbox = new InMemoryOutboxStore(d);
        final InMemoryUnitOfWork uow = new InMemoryUnitOfWork(d, 5, GRACE);

        Fixture(int offerCount) {
            ls.save(listing);
            if (offerCount == 1) {
                offers.save(offerA);
            }
            for (int i = 0; i < offerCount && offerCount > 1; i++) {
                offers.save(Offer.place("O" + i, "L", "buyer-" + i, 100 + i, "k" + i, NOW.plusMillis(i)));
            }
        }

        /** Runs n concurrent accepts released together; true = succeeded, false = business-rule failure. */
        List<Boolean> race(int n, java.util.function.IntFunction<String> offerFor) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(n);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String offerId = offerFor.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        accept(uow, offerId);
                        return true;
                    } catch (BusinessRuleException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (var fu : futures) {
                results.add(fu.get());
            }
            pool.shutdown();
            return results;
        }
    }
}
