package com.vx.marketplace.adapter.out.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.vx.marketplace.application.port.EventPublisher;
import com.vx.marketplace.domain.DomainEvent;

import java.util.concurrent.TimeUnit;

/**
 * Publishes domain events to a Pub/Sub topic and waits for the server ack, so a failed publish fails the request
 * (no silent loss). The transactional-outbox upgrade is tracked in docs/backlog.md.
 */
public class PubSubEventPublisher implements EventPublisher, AutoCloseable {

    private final Publisher publisher;
    private final ObjectMapper mapper;

    public PubSubEventPublisher(Publisher publisher, ObjectMapper mapper) {
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @Override
    public void publish(DomainEvent event) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event.type(), e);
        }
        PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(json))
                .putAttributes("type", event.type())
                .putAttributes("aggregateId", event.aggregateId())
                .build();
        Futures.await(publisher.publish(message));
    }

    @Override
    public void close() throws InterruptedException {
        publisher.shutdown();
        publisher.awaitTermination(5, TimeUnit.SECONDS);
    }
}
