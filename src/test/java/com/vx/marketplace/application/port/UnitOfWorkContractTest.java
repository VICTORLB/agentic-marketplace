package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.ListingStatus;
import com.vx.marketplace.domain.NotFoundException;
import com.vx.marketplace.domain.Offer;
import com.vx.marketplace.domain.OfferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outcome-only behaviour every UnitOfWork adapter must show. Adapter-specific conflict injection lives in adapter tests. */
public abstract class UnitOfWorkContractTest {

    protected static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    protected abstract UnitOfWork uow(int maxAttempts);

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
    void twoConcurrentAcceptsOfDifferentOffersLeaveExactlyOneAccepted() throws Exception {
        Offer offerB = Offer.place("B", "L", "buyer-2", 800, "k2", NOW);
        offers().save(offerB);
        UnitOfWork uow = uow(5);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
        for (String offerId : List.of("A", "B")) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    uow.execute(tx -> {
                        Listing l = tx.findListing("L").orElseThrow();
                        Offer chosen = tx.findOffer(offerId).orElseThrow();
                        var outcome = l.acceptOffer(chosen, tx.findOffersByListing("L"));
                        tx.save(outcome.sold());
                        tx.save(outcome.accepted());
                        outcome.rejected().forEach(tx::save);
                        return null;
                    });
                    return true;
                } catch (com.vx.marketplace.domain.BusinessRuleException e) {
                    return false; // includes ConcurrencyConflictException
                }
            }));
        }
        start.countDown();
        int winners = 0;
        for (var f : results) {
            winners += f.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
        assertThat(offers().findByListingId("L")).filteredOn(o -> o.status() == OfferStatus.ACCEPTED).hasSize(1);
        assertThat(listings().findById("L")).map(Listing::status).contains(ListingStatus.SOLD);
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
        assertThat(offers().findById("A")).map(Offer::status).contains(OfferStatus.ACCEPTED);
    }
}
