package com.vx.marketplace.domain;

import java.time.Instant;
import java.util.Objects;

/** A buyer's offer on a listing. Immutable: state changes return a new instance. */
public record Offer(
        String id,
        String listingId,
        String buyerId,
        long amountCents,
        OfferStatus status,
        String idempotencyKey,
        Instant createdAt) {

    public Offer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (buyerId == null || buyerId.isBlank()) {
            throw new IllegalArgumentException("buyerId is required");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }

    public static Offer place(String id, String listingId, String buyerId, long amountCents, String idempotencyKey, Instant now) {
        return new Offer(id, listingId, buyerId, amountCents, OfferStatus.PENDING, idempotencyKey, now);
    }

    public boolean isPending() {
        return status == OfferStatus.PENDING;
    }

    public Offer accept() {
        return transitionTo(OfferStatus.ACCEPTED);
    }

    public Offer reject() {
        return transitionTo(OfferStatus.REJECTED);
    }

    private Offer transitionTo(OfferStatus next) {
        if (!isPending()) {
            throw new BusinessRuleException("Offer " + id + " is " + status + ", expected PENDING");
        }
        return new Offer(id, listingId, buyerId, amountCents, next, idempotencyKey, createdAt);
    }
}
