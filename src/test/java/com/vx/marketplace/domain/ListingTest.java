package com.vx.marketplace.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private final Listing active = Listing.create("L", "seller", "Bike", 1000, "USD", NOW);
    private final Offer a = Offer.place("A", "L", "buyer-1", 900, "k1", NOW);
    private final Offer b = Offer.place("B", "L", "buyer-2", 800, "k2", NOW);

    @Test
    void acceptOfferSellsListingAcceptsChosenAndRejectsOtherPendingOffers() {
        Offer done = Offer.place("C", "L", "buyer-3", 700, "k3", NOW).reject();

        AcceptOutcome out = active.acceptOffer(a, List.of(a, b, done));

        assertThat(out.alreadyAccepted()).isFalse();
        assertThat(out.sold().status()).isEqualTo(ListingStatus.SOLD);
        assertThat(out.accepted().status()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(out.rejected()).extracting(Offer::id).containsExactly("B");
        assertThat(out.rejected()).allMatch(o -> o.status() == OfferStatus.REJECTED);
    }

    @Test
    void acceptOfferIsNoOpWhenChosenOfferWasAlreadyAccepted() {
        AcceptOutcome first = active.acceptOffer(a, List.of(a, b));

        AcceptOutcome retry = first.sold().acceptOffer(first.accepted(), List.of(first.accepted(), first.rejected().get(0)));

        assertThat(retry.alreadyAccepted()).isTrue();
        assertThat(retry.accepted()).isEqualTo(first.accepted());
        assertThat(retry.rejected()).isEmpty();
    }

    @Test
    void acceptOfferOfDifferentOfferOnSoldListingIsRejected() {
        AcceptOutcome first = active.acceptOffer(a, List.of(a, b));

        assertThatThrownBy(() -> first.sold().acceptOffer(first.rejected().get(0), List.of()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void acceptOfferRejectsNonPendingOfferOnActiveListing() {
        assertThatThrownBy(() -> active.acceptOffer(b.reject(), List.of()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void acceptOfferRejectsOfferFromAnotherListing() {
        Offer other = Offer.place("X", "other", "buyer-1", 900, "k9", NOW);

        assertThatThrownBy(() -> active.acceptOffer(other, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptOfferRejectsListOfOffersContainingAnotherListingsOffer() {
        Offer other = Offer.place("X", "other", "buyer-3", 700, "k9", NOW);

        assertThatThrownBy(() -> active.acceptOffer(a, List.of(a, other)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
