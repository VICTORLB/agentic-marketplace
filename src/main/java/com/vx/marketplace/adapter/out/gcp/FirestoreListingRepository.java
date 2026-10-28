package com.vx.marketplace.adapter.out.gcp;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.vx.marketplace.application.port.ListingRepository;
import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.ListingStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public class FirestoreListingRepository implements ListingRepository {

    static final String COLLECTION = "listings";

    private final Firestore db;

    public FirestoreListingRepository(Firestore db) {
        this.db = db;
    }

    @Override
    public void save(Listing l) {
        Map<String, Object> doc = Map.of(
                "sellerId", l.sellerId(),
                "title", l.title(),
                "priceCents", l.priceCents(),
                "currency", l.currency(),
                "status", l.status().name(),
                "createdAt", l.createdAt().toString());
        Futures.await(db.collection(COLLECTION).document(l.id()).set(doc));
    }

    @Override
    public Optional<Listing> findById(String id) {
        DocumentSnapshot snap = Futures.await(db.collection(COLLECTION).document(id).get());
        if (!snap.exists()) {
            return Optional.empty();
        }
        return Optional.of(new Listing(
                snap.getId(),
                snap.getString("sellerId"),
                snap.getString("title"),
                snap.getLong("priceCents"),
                snap.getString("currency"),
                ListingStatus.valueOf(snap.getString("status")),
                Instant.parse(snap.getString("createdAt"))));
    }
}
