package com.vx.marketplace.adapter.out.gcp;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.vx.marketplace.application.port.OfferRepository;
import com.vx.marketplace.domain.Offer;
import com.vx.marketplace.domain.OfferStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FirestoreOfferRepository implements OfferRepository {

    static final String COLLECTION = "offers";

    private final Firestore db;

    public FirestoreOfferRepository(Firestore db) {
        this.db = db;
    }

    @Override
    public void save(Offer o) {
        Map<String, Object> doc = Map.of(
                "listingId", o.listingId(),
                "buyerId", o.buyerId(),
                "amountCents", o.amountCents(),
                "status", o.status().name(),
                "idempotencyKey", o.idempotencyKey(),
                "createdAt", o.createdAt().toString());
        Futures.await(db.collection(COLLECTION).document(o.id()).set(doc));
    }

    @Override
    public Optional<Offer> findById(String id) {
        DocumentSnapshot snap = Futures.await(db.collection(COLLECTION).document(id).get());
        return snap.exists() ? Optional.of(toOffer(snap)) : Optional.empty();
    }

    @Override
    public List<Offer> findByListingId(String listingId) {
        var docs = Futures.await(db.collection(COLLECTION).whereEqualTo("listingId", listingId).get()).getDocuments();
        // Sorted in memory to avoid needing a composite index for this demo.
        return docs.stream().map(FirestoreOfferRepository::toOffer)
                .sorted(Comparator.comparing(Offer::createdAt))
                .toList();
    }

    @Override
    public Optional<Offer> findByBuyerAndIdempotencyKey(String buyerId, String idempotencyKey) {
        var docs = Futures.await(db.collection(COLLECTION)
                .whereEqualTo("buyerId", buyerId)
                .whereEqualTo("idempotencyKey", idempotencyKey)
                .limit(1)
                .get()).getDocuments();
        return docs.stream().findFirst().map(FirestoreOfferRepository::toOffer);
    }

    private static Offer toOffer(DocumentSnapshot snap) {
        return new Offer(
                snap.getId(),
                snap.getString("listingId"),
                snap.getString("buyerId"),
                snap.getLong("amountCents"),
                OfferStatus.valueOf(snap.getString("status")),
                snap.getString("idempotencyKey"),
                Instant.parse(snap.getString("createdAt")));
    }
}
