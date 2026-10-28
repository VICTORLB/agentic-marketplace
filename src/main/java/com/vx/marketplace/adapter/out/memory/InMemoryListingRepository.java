package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.ListingRepository;
import com.vx.marketplace.domain.Listing;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Local/dev and test adapter. Not shared across Cloud Run instances. */
public class InMemoryListingRepository implements ListingRepository {

    private final Map<String, Listing> store = new ConcurrentHashMap<>();

    @Override
    public void save(Listing listing) {
        store.put(listing.id(), listing);
    }

    @Override
    public Optional<Listing> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }
}
