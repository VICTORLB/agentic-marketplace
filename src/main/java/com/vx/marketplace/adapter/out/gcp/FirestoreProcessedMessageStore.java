package com.vx.marketplace.adapter.out.gcp;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.vx.marketplace.application.port.ProcessedMessageStore;

import java.time.Instant;
import java.util.Map;

/** De-duplicates Pub/Sub deliveries across all Cloud Run instances using a Firestore transaction. */
public class FirestoreProcessedMessageStore implements ProcessedMessageStore {

    static final String COLLECTION = "processed_messages";

    private final Firestore db;

    public FirestoreProcessedMessageStore(Firestore db) {
        this.db = db;
    }

    @Override
    public boolean markProcessed(String messageId) {
        DocumentReference ref = db.collection(COLLECTION).document(messageId);
        return Futures.await(db.runTransaction(tx -> {
            if (tx.get(ref).get().exists()) {
                return false;
            }
            tx.set(ref, Map.of("processedAt", Instant.now().toString()));
            return true;
        }));
    }
}
