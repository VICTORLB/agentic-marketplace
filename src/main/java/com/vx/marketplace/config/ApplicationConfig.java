package com.vx.marketplace.config;

import com.vx.marketplace.application.MarketplaceService;
import com.vx.marketplace.application.OfferEventHandler;
import com.vx.marketplace.application.port.EventPublisher;
import com.vx.marketplace.application.port.ListingRepository;
import com.vx.marketplace.application.port.OfferRepository;
import com.vx.marketplace.application.port.ProcessedMessageStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

/** Wires framework-free use cases to whichever adapters the active profile provides. */
@Configuration
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MarketplaceService marketplaceService(ListingRepository listings, OfferRepository offers, EventPublisher events,
                                          Clock clock) {
        return new MarketplaceService(listings, offers, events, clock, () -> UUID.randomUUID().toString());
    }

    @Bean
    OfferEventHandler offerEventHandler(ProcessedMessageStore processed) {
        return new OfferEventHandler(processed);
    }
}
