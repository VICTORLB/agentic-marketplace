package com.vx.marketplace.domain;

import java.util.List;

/** Result of {@link Listing#acceptOffer}: the new state of every aggregate touched by an accept. */
public record AcceptOutcome(Listing sold, Offer accepted, List<Offer> rejected, boolean alreadyAccepted) {
    public AcceptOutcome {
        rejected = List.copyOf(rejected);
    }
}
