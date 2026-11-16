package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.OfferRepository;
import com.vx.marketplace.domain.Offer;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Local/dev and test adapter. Not shared across Cloud Run instances. */
public class InMemoryOfferRepository implements OfferRepository {

    private final InMemoryDatabase db;

    public InMemoryOfferRepository() {
        this(new InMemoryDatabase());
    }

    public InMemoryOfferRepository(InMemoryDatabase db) {
        this.db = db;
    }

    @Override
    public void save(Offer offer) {
        db.putOffer(offer);
    }

    @Override
    public Optional<Offer> findById(String id) {
        return Optional.ofNullable(db.offer(id)).map(InMemoryDatabase.Versioned::value);
    }

    @Override
    public List<Offer> findByListingId(String listingId) {
        return db.offersOfListing(listingId).stream()
                .map(InMemoryDatabase.Versioned::value)
                .sorted(Comparator.comparing(Offer::createdAt))
                .toList();
    }

    @Override
    public Optional<Offer> findByBuyerAndIdempotencyKey(String buyerId, String idempotencyKey) {
        return db.allOffers().stream()
                .map(InMemoryDatabase.Versioned::value)
                .filter(o -> o.buyerId().equals(buyerId) && o.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }
}
