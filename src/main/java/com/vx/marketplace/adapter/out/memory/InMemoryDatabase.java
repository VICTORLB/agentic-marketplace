package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.Offer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** Versioned in-memory state shared by the in-memory repositories, unit of work and outbox. One commit lock. */
public class InMemoryDatabase {

    record Versioned<T>(T value, long version) { }

    final ReentrantLock lock = new ReentrantLock();
    final Map<String, InMemoryOutboxStore.Entry> outbox = new ConcurrentHashMap<>();
    private final Map<String, Versioned<Listing>> listings = new ConcurrentHashMap<>();
    private final Map<String, Versioned<Offer>> offers = new ConcurrentHashMap<>();

    Versioned<Listing> listing(String id) {
        return listings.get(id);
    }

    Versioned<Offer> offer(String id) {
        return offers.get(id);
    }

    List<Versioned<Offer>> offersOfListing(String listingId) {
        return offers.values().stream().filter(v -> v.value().listingId().equals(listingId)).toList();
    }

    List<Versioned<Offer>> allOffers() {
        return List.copyOf(offers.values());
    }

    /** id -> version of every offer currently on the listing; used to detect phantoms at commit. */
    Map<String, Long> offerVersionsOfListing(String listingId) {
        return offersOfListing(listingId).stream()
                .collect(Collectors.toMap(v -> v.value().id(), Versioned::version));
    }

    void putListing(Listing listing) {
        put(listings, listing.id(), listing);
    }

    void putOffer(Offer offer) {
        put(offers, offer.id(), offer);
    }

    private <T> void put(Map<String, Versioned<T>> map, String id, T value) {
        lock.lock();
        try {
            map.merge(id, new Versioned<>(value, 1), (old, ignored) -> new Versioned<>(value, old.version() + 1));
        } finally {
            lock.unlock();
        }
    }
}
