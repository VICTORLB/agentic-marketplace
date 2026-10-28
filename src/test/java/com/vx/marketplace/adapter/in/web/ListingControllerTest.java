package com.vx.marketplace.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end through HTTP with the default (in-memory) profile. */
@SpringBootTest
@AutoConfigureMockMvc
class ListingControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void fullOfferFlow() throws Exception {
        String listingId = createListing();

        String offerJson = mvc.perform(post("/api/listings/{id}/offers", listingId)
                        .header("X-User-Id", "buyer-1")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\": 9000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String offerId = mapper.readTree(offerJson).get("id").asText();

        // Same Idempotency-Key -> 200 with the same offer, not a duplicate.
        mvc.perform(post("/api/listings/{id}/offers", listingId)
                        .header("X-User-Id", "buyer-1")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\": 9000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(offerId));

        mvc.perform(post("/api/listings/{id}/offers/{offerId}/accept", listingId, offerId)
                        .header("X-User-Id", "seller-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(get("/api/listings/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"));
    }

    @Test
    void invalidListingIsBadRequest() throws Exception {
        mvc.perform(post("/api/listings")
                        .header("X-User-Id", "seller-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"\", \"priceCents\": -1, \"currency\": \"brl\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        String listingId = createListing();
        mvc.perform(post("/api/listings/{id}/offers", listingId)
                        .header("X-User-Id", "buyer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\": 9000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonSellerCannotAccept() throws Exception {
        String listingId = createListing();
        mvc.perform(post("/api/listings/{id}/offers/{offerId}/accept", listingId, "whatever")
                        .header("X-User-Id", "buyer-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pubSubPushIsAcked() throws Exception {
        String data = Base64.getEncoder().encodeToString(
                "{\"offerId\":\"o1\",\"listingId\":\"l1\"}".getBytes(StandardCharsets.UTF_8));
        String envelope = """
                {"message": {"data": "%s", "messageId": "m-1", "attributes": {"type": "offer.placed"}},
                 "subscription": "projects/p/subscriptions/s"}
                """.formatted(data);

        mvc.perform(post("/pubsub/push").contentType(MediaType.APPLICATION_JSON).content(envelope))
                .andExpect(status().isNoContent());
    }

    private String createListing() throws Exception {
        String body = mvc.perform(post("/api/listings")
                        .header("X-User-Id", "seller-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Road bike\", \"priceCents\": 10000, \"currency\": \"BRL\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        return node.get("id").asText();
    }
}
