package com.vx.marketplace.application;

import com.vx.marketplace.adapter.out.memory.InMemoryListingRepository;
import com.vx.marketplace.adapter.out.memory.InMemoryOfferRepository;
import com.vx.marketplace.adapter.out.memory.LoggingEventPublisher;
import com.vx.marketplace.domain.BusinessRuleException;
import com.vx.marketplace.domain.DomainEvent;
import com.vx.marketplace.domain.ForbiddenException;
import com.vx.marketplace.domain.ListingStatus;
import com.vx.marketplace.domain.NotFoundException;
import com.vx.marketplace.domain.OfferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketplaceServiceTest {

    private static final String SELLER = "seller-1";
    private static final String BUYER = "buyer-1";

    private InMemoryOfferRepository offers;
    private LoggingEventPublisher events;
    private MarketplaceService service;

    @BeforeEach
    void setUp() {
        offers = new InMemoryOfferRepository();
        events = new LoggingEventPublisher();
        AtomicInteger seq = new AtomicInteger();
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
        service = new MarketplaceService(new InMemoryListingRepository(), offers, events, clock,
                () -> "id-" + seq.incrementAndGet());
    }

    @Test
    void createsActiveListingAndPublishesEvent() {
        var listing = service.createListing(SELLER, "  Road bike  ", 150_000, "BRL");

        assertThat(listing.status()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.title()).isEqualTo("Road bike");
        assertThat(events.published()).singleElement().isInstanceOf(DomainEvent.ListingCreated.class);
    }

    @Test
    void unknownListingIsNotFound() {
        assertThatThrownBy(() -> service.getListing("nope")).isInstanceOf(NotFoundException.class);
    }

    @Nested
    class PlaceOffer {

        @Test
        void placesPendingOffer() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");

            var result = service.placeOffer(listing.id(), BUYER, 90_00, "key-1");

            assertThat(result.created()).isTrue();
            assertThat(result.offer().status()).isEqualTo(OfferStatus.PENDING);
            assertThat(events.published()).last().isInstanceOf(DomainEvent.OfferPlaced.class);
        }

        @Test
        void retryWithSameKeyReturnsOriginalOfferWithoutDuplicateEvent() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");
            var first = service.placeOffer(listing.id(), BUYER, 90_00, "key-1");
            int eventsBefore = events.published().size();

            var retry = service.placeOffer(listing.id(), BUYER, 90_00, "key-1");

            assertThat(retry.created()).isFalse();
            assertThat(retry.offer().id()).isEqualTo(first.offer().id());
            assertThat(offers.findByListingId(listing.id())).hasSize(1);
            assertThat(events.published()).hasSize(eventsBefore);
        }

        @Test
        void reusingKeyForDifferentAmountIsRejected() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");
            service.placeOffer(listing.id(), BUYER, 90_00, "key-1");

            assertThatThrownBy(() -> service.placeOffer(listing.id(), BUYER, 95_00, "key-1"))
                    .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void sellerCannotOfferOnOwnListing() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");

            assertThatThrownBy(() -> service.placeOffer(listing.id(), SELLER, 90_00, "key-1"))
                    .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void soldListingRejectsNewOffers() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");
            var offer = service.placeOffer(listing.id(), BUYER, 90_00, "key-1").offer();
            service.acceptOffer(listing.id(), offer.id(), SELLER);

            assertThatThrownBy(() -> service.placeOffer(listing.id(), "buyer-2", 99_00, "key-2"))
                    .isInstanceOf(BusinessRuleException.class);
        }
    }

    @Nested
    class AcceptOffer {

        @Test
        void acceptingMarksListingSoldAndRejectsOtherPendingOffers() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");
            var winner = service.placeOffer(listing.id(), BUYER, 95_00, "k1").offer();
            var loser = service.placeOffer(listing.id(), "buyer-2", 90_00, "k2").offer();

            var accepted = service.acceptOffer(listing.id(), winner.id(), SELLER);

            assertThat(accepted.status()).isEqualTo(OfferStatus.ACCEPTED);
            assertThat(service.getListing(listing.id()).status()).isEqualTo(ListingStatus.SOLD);
            assertThat(offers.findById(loser.id()).orElseThrow().status()).isEqualTo(OfferStatus.REJECTED);
            assertThat(events.published()).last().isInstanceOf(DomainEvent.OfferAccepted.class);
        }

        @Test
        void onlySellerCanAccept() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");
            var offer = service.placeOffer(listing.id(), BUYER, 95_00, "k1").offer();

            assertThatThrownBy(() -> service.acceptOffer(listing.id(), offer.id(), BUYER))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void offerFromAnotherListingIsNotFound() {
            var a = service.createListing(SELLER, "Bike", 100_00, "BRL");
            var b = service.createListing(SELLER, "Helmet", 20_00, "BRL");
            var offerOnB = service.placeOffer(b.id(), BUYER, 15_00, "k1").offer();

            assertThatThrownBy(() -> service.acceptOffer(a.id(), offerOnB.id(), SELLER))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void cannotAcceptTwice() {
            var listing = service.createListing(SELLER, "Bike", 100_00, "BRL");
            var offer = service.placeOffer(listing.id(), BUYER, 95_00, "k1").offer();
            service.acceptOffer(listing.id(), offer.id(), SELLER);

            assertThatThrownBy(() -> service.acceptOffer(listing.id(), offer.id(), SELLER))
                    .isInstanceOf(BusinessRuleException.class);
        }
    }
}
