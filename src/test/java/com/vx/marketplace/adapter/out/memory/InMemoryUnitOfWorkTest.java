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

import java.time.Duration;
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
}
