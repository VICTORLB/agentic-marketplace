package com.vx.marketplace.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** An item a seller puts up for sale. Immutable: state changes return a new instance. */
public record Listing(
        String id,
        String sellerId,
        String title,
        long priceCents,
        String currency,
        ListingStatus status,
        Instant createdAt) {

    public Listing {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sellerId == null || sellerId.isBlank()) {
            throw new IllegalArgumentException("sellerId is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (priceCents <= 0) {
            throw new IllegalArgumentException("priceCents must be positive");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code");
        }
    }

    public static Listing create(String id, String sellerId, String title, long priceCents, String currency, Instant now) {
        return new Listing(id, sellerId, title.strip(), priceCents, currency, ListingStatus.ACTIVE, now);
    }

    public boolean isActive() {
        return status == ListingStatus.ACTIVE;
    }

    public boolean isOwnedBy(String userId) {
        return sellerId.equals(userId);
    }

    public Listing markSold() {
        if (!isActive()) {
            throw new BusinessRuleException("Listing " + id + " is not active");
        }
        return new Listing(id, sellerId, title, priceCents, currency, ListingStatus.SOLD, createdAt);
    }

    /**
     * Pure accept rule. Re-accepting the already accepted offer of a sold listing is a no-op (idempotent retry);
     * otherwise the listing must be active and the chosen offer pending. Every other pending offer is rejected.
     */
    public AcceptOutcome acceptOffer(Offer chosen, List<Offer> allOffersOnListing) {
        if (!id.equals(chosen.listingId())) {
            throw new IllegalArgumentException("Offer " + chosen.id() + " does not belong to listing " + id);
        }
        if (status == ListingStatus.SOLD && chosen.status() == OfferStatus.ACCEPTED) {
            return new AcceptOutcome(this, chosen, List.of(), true);
        }
        Listing sold = markSold();
        Offer accepted = chosen.accept();
        List<Offer> rejected = allOffersOnListing.stream()
                .filter(o -> !o.id().equals(chosen.id()) && o.isPending())
                .map(Offer::reject)
                .toList();
        return new AcceptOutcome(sold, accepted, rejected, false);
    }
}
