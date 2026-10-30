package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.ListingStatus;
import com.vx.marketplace.domain.NotFoundException;
import com.vx.marketplace.domain.Offer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour every UnitOfWork adapter must show. Subclasses supply the adapter and a conflict-injection hook. */
public abstract class UnitOfWorkContractTest {

    protected static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    protected abstract UnitOfWork uow(int maxAttempts);

    /** Runs {@code hook(attempt)} after the function ran and before the commit is validated. */
    protected abstract void beforeCommit(Consumer<Integer> hook);

    protected abstract ListingRepository listings();

    protected abstract OfferRepository offers();

    protected Listing listing = Listing.create("L", "seller", "Bike", 1000, "USD", NOW);
    protected Offer offerA = Offer.place("A", "L", "buyer-1", 900, "k1", NOW);

    @BeforeEach
    void seed() {
        listings().save(listing);
        offers().save(offerA);
    }

    @Test
    void rollsBackAllWritesWhenFunctionThrows() {
        UnitOfWork uow = uow(3);

        assertThatThrownBy(() -> uow.execute(tx -> {
            tx.findListing("L");
            tx.save(listing.markSold());
            tx.save(offerA.accept());
            throw new NotFoundException("boom");
        })).isInstanceOf(NotFoundException.class);

        assertThat(listings().findById("L")).contains(listing);
        assertThat(offers().findById("A")).contains(offerA);
    }

    @Test
    void rerunsFunctionWhenReadDocumentChangesBeforeCommit() {
        UnitOfWork uow = uow(3);
        beforeCommit(attempt -> {
            if (attempt == 1) {
                listings().save(listing.markSold());
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
        beforeCommit(attempt -> {
            if (attempt == 1) {
                offers().save(Offer.place("B", "L", "buyer-2", 800, "k2", NOW));
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
        beforeCommit(attempt -> listings().save(new Listing("L", "seller", "Bike v" + attempt, 1000, "USD",
                ListingStatus.ACTIVE, NOW)));
        List<Integer> runs = new ArrayList<>();

        assertThatThrownBy(() -> uow.execute(tx -> {
            runs.add(1);
            tx.findListing("L");
            tx.save(offerA.accept());
            return null;
        })).isInstanceOf(ConcurrencyConflictException.class).hasMessageContaining("listing L");

        assertThat(runs).hasSize(3);
        assertThat(offers().findById("A")).contains(offerA);
    }

    @Test
    void rejectsReadAfterWrite() {
        UnitOfWork uow = uow(3);

        assertThatThrownBy(() -> uow.execute(tx -> {
            tx.save(listing.markSold());
            return tx.findListing("L");
        })).isInstanceOf(IllegalStateException.class).hasMessage("reads must precede writes in a transaction");
    }

    @Test
    void appliesAllBufferedWritesOnCommit() {
        uow(3).execute(tx -> {
            tx.findListing("L");
            tx.findOffer("A");
            tx.save(listing.markSold());
            tx.save(offerA.accept());
            return null;
        });

        assertThat(listings().findById("L")).map(Listing::status).contains(ListingStatus.SOLD);
        assertThat(offers().findById("A")).map(Offer::status).contains(com.vx.marketplace.domain.OfferStatus.ACCEPTED);
    }
}
