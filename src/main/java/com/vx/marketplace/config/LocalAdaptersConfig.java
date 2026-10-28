package com.vx.marketplace.config;

import com.vx.marketplace.adapter.out.memory.InMemoryListingRepository;
import com.vx.marketplace.adapter.out.memory.InMemoryOfferRepository;
import com.vx.marketplace.adapter.out.memory.InMemoryProcessedMessageStore;
import com.vx.marketplace.adapter.out.memory.LoggingEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Default profile: everything in memory, no cloud credentials needed. */
@Configuration
@Profile("!gcp")
public class LocalAdaptersConfig {

    @Bean
    InMemoryListingRepository listingRepository() {
        return new InMemoryListingRepository();
    }

    @Bean
    InMemoryOfferRepository offerRepository() {
        return new InMemoryOfferRepository();
    }

    @Bean
    InMemoryProcessedMessageStore processedMessageStore() {
        return new InMemoryProcessedMessageStore();
    }

    @Bean
    LoggingEventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }
}
