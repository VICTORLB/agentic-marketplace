package com.vx.marketplace.domain;

import java.time.Instant;

/** Facts the service publishes after a state change. Serialized as JSON onto Pub/Sub. */
public sealed interface DomainEvent {

    String type();

    String aggregateId();

    Instant occurredAt();

    record ListingCreated(String listingId, String sellerId, Instant occurredAt) implements DomainEvent {
        public String type() { return "listing.created"; }
        public String aggregateId() { return listingId; }
    }

    record OfferPlaced(String offerId, String listingId, String buyerId, long amountCents, Instant occurredAt)
            implements DomainEvent {
        public String type() { return "offer.placed"; }
        public String aggregateId() { return listingId; }
    }

    record OfferAccepted(String offerId, String listingId, String buyerId, String sellerId, long amountCents,
                         Instant occurredAt) implements DomainEvent {
        public String type() { return "offer.accepted"; }
        public String aggregateId() { return listingId; }
    }
}
