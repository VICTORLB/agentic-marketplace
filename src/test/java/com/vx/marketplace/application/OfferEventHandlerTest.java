package com.vx.marketplace.application;

import com.vx.marketplace.adapter.out.memory.InMemoryProcessedMessageStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfferEventHandlerTest {

    private final OfferEventHandler handler = new OfferEventHandler(new InMemoryProcessedMessageStore());

    @Test
    void handlesFirstDeliveryAndSkipsDuplicates() {
        var payload = Map.<String, Object>of("offerId", "o1", "listingId", "l1");

        assertThat(handler.handle("m1", "offer.placed", payload)).isEqualTo(OfferEventHandler.Outcome.HANDLED);
        assertThat(handler.handle("m1", "offer.placed", payload)).isEqualTo(OfferEventHandler.Outcome.DUPLICATE);
    }

    @Test
    void ignoresUnknownEventTypes() {
        assertThat(handler.handle("m2", "listing.created", Map.of())).isEqualTo(OfferEventHandler.Outcome.IGNORED);
    }

    @Test
    void requiresMessageId() {
        assertThatThrownBy(() -> handler.handle(" ", "offer.placed", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
