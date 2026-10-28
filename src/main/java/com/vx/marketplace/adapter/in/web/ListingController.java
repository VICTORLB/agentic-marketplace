package com.vx.marketplace.adapter.in.web;

import com.vx.marketplace.application.MarketplaceService;
import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.Offer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.List;

/**
 * REST API. The caller identity comes from the X-User-Id header to keep the demo small; in production it would come
 * from a verified identity (e.g. IAP or a JWT validated at the edge).
 */
@RestController
@RequestMapping("/api/listings")
public class ListingController {

    static final String USER_HEADER = "X-User-Id";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final MarketplaceService service;

    public ListingController(MarketplaceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ListingResponse> create(@RequestHeader(USER_HEADER) String userId,
                                                  @Valid @RequestBody CreateListingRequest request) {
        Listing listing = service.createListing(userId, request.title(), request.priceCents(), request.currency());
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(listing.id()).toUri();
        return ResponseEntity.created(location).body(ListingResponse.from(listing));
    }

    @GetMapping("/{listingId}")
    public ListingResponse get(@PathVariable String listingId) {
        return ListingResponse.from(service.getListing(listingId));
    }

    @GetMapping("/{listingId}/offers")
    public List<OfferResponse> offers(@PathVariable String listingId) {
        return service.listOffers(listingId).stream().map(OfferResponse::from).toList();
    }

    @PostMapping("/{listingId}/offers")
    public ResponseEntity<OfferResponse> placeOffer(@PathVariable String listingId,
                                                    @RequestHeader(USER_HEADER) String userId,
                                                    @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                    @Valid @RequestBody PlaceOfferRequest request) {
        var result = service.placeOffer(listingId, userId, request.amountCents(), idempotencyKey);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(OfferResponse.from(result.offer()));
    }

    @PostMapping("/{listingId}/offers/{offerId}/accept")
    public OfferResponse accept(@PathVariable String listingId,
                                @PathVariable String offerId,
                                @RequestHeader(USER_HEADER) String userId) {
        return OfferResponse.from(service.acceptOffer(listingId, offerId, userId));
    }

    public record CreateListingRequest(
            @NotBlank @Size(max = 140) String title,
            @Positive long priceCents,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record PlaceOfferRequest(@Positive long amountCents) {
    }

    public record ListingResponse(String id, String sellerId, String title, long priceCents, String currency,
                                  String status, Instant createdAt) {
        static ListingResponse from(Listing l) {
            return new ListingResponse(l.id(), l.sellerId(), l.title(), l.priceCents(), l.currency(),
                    l.status().name(), l.createdAt());
        }
    }

    public record OfferResponse(String id, String listingId, String buyerId, long amountCents, String status,
                                Instant createdAt) {
        static OfferResponse from(Offer o) {
            return new OfferResponse(o.id(), o.listingId(), o.buyerId(), o.amountCents(), o.status().name(),
                    o.createdAt());
        }
    }
}
