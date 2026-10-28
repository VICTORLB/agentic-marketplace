package com.vx.marketplace.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.ServiceOptions;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.TopicName;
import com.vx.marketplace.adapter.out.gcp.FirestoreListingRepository;
import com.vx.marketplace.adapter.out.gcp.FirestoreOfferRepository;
import com.vx.marketplace.adapter.out.gcp.FirestoreProcessedMessageStore;
import com.vx.marketplace.adapter.out.gcp.PubSubEventPublisher;
import com.vx.marketplace.adapter.out.gcp.PushTokenVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;

/** "gcp" profile (set on Cloud Run): Firestore for state, Pub/Sub for events, credentials from the runtime SA. */
@Configuration
@Profile("gcp")
public class GcpAdaptersConfig {

    @Bean(destroyMethod = "close")
    Firestore firestore() {
        return FirestoreOptions.getDefaultInstance().getService();
    }

    @Bean
    FirestoreListingRepository listingRepository(Firestore db) {
        return new FirestoreListingRepository(db);
    }

    @Bean
    FirestoreOfferRepository offerRepository(Firestore db) {
        return new FirestoreOfferRepository(db);
    }

    @Bean
    FirestoreProcessedMessageStore processedMessageStore(Firestore db) {
        return new FirestoreProcessedMessageStore(db);
    }

    @Bean(destroyMethod = "close")
    PubSubEventPublisher eventPublisher(@Value("${marketplace.pubsub.topic}") String topic, ObjectMapper mapper)
            throws IOException {
        Publisher publisher = Publisher.newBuilder(TopicName.of(ServiceOptions.getDefaultProjectId(), topic)).build();
        return new PubSubEventPublisher(publisher, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "marketplace.pubsub.push.verify", havingValue = "true")
    PushTokenVerifier pushTokenVerifier(@Value("${marketplace.pubsub.push.audience}") String audience,
                                        @Value("${marketplace.pubsub.push.service-account}") String serviceAccount) {
        return new PushTokenVerifier(audience, serviceAccount);
    }
}
