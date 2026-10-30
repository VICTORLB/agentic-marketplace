package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.Offer;

import java.util.List;
import java.util.Optional;

/** Transactional view. Reads must precede writes; writes are buffered and applied at commit. */
public interface TransactionContext {
    Optional<Listing> findListing(String id);

    Optional<Offer> findOffer(String id);

    List<Offer> findOffersByListing(String listingId);

    void save(Listing listing);

    void save(Offer offer);

    void enqueue(OutboxMessage message);
}
