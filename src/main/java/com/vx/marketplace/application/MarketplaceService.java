package com.vx.marketplace.application;

import com.vx.marketplace.application.port.EventPublisher;
import com.vx.marketplace.application.port.ListingRepository;
import com.vx.marketplace.application.port.OfferRepository;
import com.vx.marketplace.domain.BusinessRuleException;
import com.vx.marketplace.domain.DomainEvent;
import com.vx.marketplace.domain.ForbiddenException;
import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.NotFoundException;
import com.vx.marketplace.domain.Offer;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * Use cases for listings and offers. Framework-free on purpose: wired in config/, tested with in-memory adapters.
 *
 * <p>Known limitation (see docs/backlog.md, item 1): {@link #acceptOffer} performs several writes and then publishes
 * an event without a transaction or outbox, so a crash midway can leave partial state or a lost event.
 */
public class MarketplaceService {

    private final ListingRepository listings;
    private final OfferRepository offers;
    private final EventPublisher events;
    private final Clock clock;
    private final Supplier<String> ids;

    public MarketplaceService(ListingRepository listings, OfferRepository offers, EventPublisher events,
                              Clock clock, Supplier<String> ids) {
        this.listings = listings;
        this.offers = offers;
        this.events = events;
        this.clock = clock;
        this.ids = ids;
    }

    public Listing createListing(String sellerId, String title, long priceCents, String currency) {
        Listing listing = Listing.create(ids.get(), sellerId, title, priceCents, currency, clock.instant());
        listings.save(listing);
        events.publish(new DomainEvent.ListingCreated(listing.id(), sellerId, clock.instant()));
        return listing;
    }

    public Listing getListing(String listingId) {
        return listings.findById(listingId)
                .orElseThrow(() -> new NotFoundException("Listing " + listingId + " not found"));
    }

    public List<Offer> listOffers(String listingId) {
        getListing(listingId);
        return offers.findByListingId(listingId);
    }

    /**
     * Places an offer. Retrying with the same (buyer, idempotency key) returns the original offer instead of creating
     * a duplicate, so clients can safely retry on timeouts.
     */
    public PlaceOfferResult placeOffer(String listingId, String buyerId, long amountCents, String idempotencyKey) {
        var existing = offers.findByBuyerAndIdempotencyKey(buyerId, idempotencyKey);
        if (existing.isPresent()) {
            Offer offer = existing.get();
            if (!offer.listingId().equals(listingId) || offer.amountCents() != amountCents) {
                throw new BusinessRuleException("Idempotency-Key was already used for a different request");
            }
            return new PlaceOfferResult(offer, false);
        }

        Listing listing = getListing(listingId);
        if (!listing.isActive()) {
            throw new BusinessRuleException("Listing " + listingId + " is no longer accepting offers");
        }
        if (listing.isOwnedBy(buyerId)) {
            throw new BusinessRuleException("Sellers cannot make offers on their own listing");
        }

        Offer offer = Offer.place(ids.get(), listingId, buyerId, amountCents, idempotencyKey, clock.instant());
        offers.save(offer);
        events.publish(new DomainEvent.OfferPlaced(offer.id(), listingId, buyerId, amountCents, clock.instant()));
        return new PlaceOfferResult(offer, true);
    }

    /** Seller accepts one offer: the listing becomes SOLD and every other pending offer is rejected. */
    public Offer acceptOffer(String listingId, String offerId, String sellerId) {
        Listing listing = getListing(listingId);
        if (!listing.isOwnedBy(sellerId)) {
            throw new ForbiddenException("Only the seller can accept offers on listing " + listingId);
        }
        Offer offer = offers.findById(offerId)
                .filter(o -> o.listingId().equals(listingId))
                .orElseThrow(() -> new NotFoundException("Offer " + offerId + " not found on listing " + listingId));

        Listing sold = listing.markSold();
        Offer accepted = offer.accept();

        listings.save(sold);
        offers.save(accepted);
        offers.findByListingId(listingId).stream()
                .filter(o -> !o.id().equals(offerId) && o.isPending())
                .map(Offer::reject)
                .forEach(offers::save);

        events.publish(new DomainEvent.OfferAccepted(accepted.id(), listingId, accepted.buyerId(), sellerId,
                accepted.amountCents(), clock.instant()));
        return accepted;
    }

    public record PlaceOfferResult(Offer offer, boolean created) {
    }
}
