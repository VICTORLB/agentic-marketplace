package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.Offer;

import java.util.List;
import java.util.Optional;

public interface OfferRepository {
    void save(Offer offer);

    Optional<Offer> findById(String id);

    List<Offer> findByListingId(String listingId);

    /** Lookup used to make offer placement idempotent per buyer. */
    Optional<Offer> findByBuyerAndIdempotencyKey(String buyerId, String idempotencyKey);
}
