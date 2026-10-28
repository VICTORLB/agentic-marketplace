package com.vx.marketplace.adapter.in.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vx.marketplace.adapter.out.gcp.PushTokenVerifier;
import com.vx.marketplace.application.OfferEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Pub/Sub push endpoint. Any 2xx acks the message; a non-2xx makes Pub/Sub retry and, after the subscription's max
 * delivery attempts, move it to the dead-letter topic. Malformed messages are acked (logged) so they don't loop.
 */
@RestController
public class PubSubPushController {

    private static final Logger LOG = LoggerFactory.getLogger(PubSubPushController.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final OfferEventHandler handler;
    private final ObjectMapper mapper;
    private final ObjectProvider<PushTokenVerifier> verifier;

    public PubSubPushController(OfferEventHandler handler, ObjectMapper mapper,
                                ObjectProvider<PushTokenVerifier> verifier) {
        this.handler = handler;
        this.mapper = mapper;
        this.verifier = verifier;
    }

    @PostMapping("/pubsub/push")
    public ResponseEntity<Void> push(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody PushEnvelope envelope) {
        PushTokenVerifier tokenVerifier = verifier.getIfAvailable();
        if (tokenVerifier != null && !tokenVerifier.isValid(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (envelope == null || envelope.message() == null || envelope.message().data() == null) {
            LOG.warn("Acking malformed push envelope");
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> payload;
        try {
            String json = new String(Base64.getDecoder().decode(envelope.message().data()), StandardCharsets.UTF_8);
            payload = mapper.readValue(json, MAP);
        } catch (Exception e) {
            LOG.warn("Acking undecodable message {}", envelope.message().messageId(), e);
            return ResponseEntity.noContent().build();
        }
        Map<String, String> attributes = envelope.message().attributes() == null ? Map.of() : envelope.message().attributes();
        var outcome = handler.handle(envelope.message().messageId(), attributes.get("type"), payload);
        LOG.info("Push message {} -> {}", envelope.message().messageId(), outcome);
        return ResponseEntity.noContent().build();
    }

    public record PushEnvelope(PushMessage message, String subscription) {
    }

    public record PushMessage(String data, String messageId, Map<String, String> attributes) {
    }
}
