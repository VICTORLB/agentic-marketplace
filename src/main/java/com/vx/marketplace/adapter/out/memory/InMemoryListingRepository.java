package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.ListingRepository;
import com.vx.marketplace.domain.Listing;

import java.util.Optional;

/** Local/dev and test adapter. Not shared across Cloud Run instances. */
public class InMemoryListingRepository implements ListingRepository {

    private final InMemoryDatabase db;

    public InMemoryListingRepository() {
        this(new InMemoryDatabase());
    }

    public InMemoryListingRepository(InMemoryDatabase db) {
        this.db = db;
    }

    @Override
    public void save(Listing listing) {
        db.putListing(listing);
    }

    @Override
    public Optional<Listing> findById(String id) {
        return Optional.ofNullable(db.listing(id)).map(InMemoryDatabase.Versioned::value);
    }
}
