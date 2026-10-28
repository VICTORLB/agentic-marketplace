package com.vx.marketplace.application.port;

import com.vx.marketplace.domain.Listing;

import java.util.Optional;

public interface ListingRepository {
    void save(Listing listing);

    Optional<Listing> findById(String id);
}
