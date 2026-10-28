package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.OfferRepository;
import com.vx.marketplace.domain.Offer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Local/dev and test adapter. Not shared across Cloud Run instances. */
public class InMemoryOfferRepository implements OfferRepository {

    private final Map<String, Offer> store = new ConcurrentHashMap<>();

    @Override
    public void save(Offer offer) {
        store.put(offer.id(), offer);
    }

    @Override
    public Optional<Offer> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Offer> findByListingId(String listingId) {
        return store.values().stream()
                .filter(o -> o.listingId().equals(listingId))
                .sorted(Comparator.comparing(Offer::createdAt))
                .toList();
    }

    @Override
    public Optional<Offer> findByBuyerAndIdempotencyKey(String buyerId, String idempotencyKey) {
        return store.values().stream()
                .filter(o -> o.buyerId().equals(buyerId) && o.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }
}
