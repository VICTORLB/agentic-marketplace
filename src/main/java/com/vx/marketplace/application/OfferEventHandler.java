package com.vx.marketplace.application;

import com.vx.marketplace.application.port.ProcessedMessageStore;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;

/**
 * Consumes offer events delivered by Pub/Sub push. Delivery is at-least-once, so every message id is de-duplicated
 * before any side effect runs.
 */
public class OfferEventHandler {

    private static final Logger LOG = System.getLogger(OfferEventHandler.class.getName());

    private final ProcessedMessageStore processed;

    public OfferEventHandler(ProcessedMessageStore processed) {
        this.processed = processed;
    }

    public Outcome handle(String messageId, String eventType, Map<String, Object> payload) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        if (!processed.markProcessed(messageId)) {
            LOG.log(Level.INFO, "Skipping duplicate delivery {0}", messageId);
            return Outcome.DUPLICATE;
        }
        switch (eventType == null ? "" : eventType) {
            // In a real system these would call a notification service; logging keeps the demo self-contained.
            case "offer.placed" -> LOG.log(Level.INFO, "Notify seller: new offer {0} on listing {1}",
                    payload.get("offerId"), payload.get("listingId"));
            case "offer.accepted" -> LOG.log(Level.INFO, "Notify buyer {0}: offer {1} accepted",
                    payload.get("buyerId"), payload.get("offerId"));
            default -> {
                LOG.log(Level.DEBUG, "Ignoring event type {0}", eventType);
                return Outcome.IGNORED;
            }
        }
        return Outcome.HANDLED;
    }

    public enum Outcome {
        HANDLED,
        DUPLICATE,
        IGNORED
    }
}
