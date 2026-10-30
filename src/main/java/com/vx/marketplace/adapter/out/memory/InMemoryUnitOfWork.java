package com.vx.marketplace.adapter.out.memory;

import com.vx.marketplace.application.port.ConcurrencyConflictException;
import com.vx.marketplace.application.port.OutboxMessage;
import com.vx.marketplace.application.port.TransactionContext;
import com.vx.marketplace.application.port.UnitOfWork;
import com.vx.marketplace.domain.Listing;
import com.vx.marketplace.domain.Offer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Optimistic-concurrency unit of work that mirrors Firestore outcomes: it records the version of every document and
 * query result read, buffers writes, and at commit (under the database lock) re-validates the reads. A change or a
 * phantom offer re-runs the function, up to maxAttempts.
 */
public class InMemoryUnitOfWork implements UnitOfWork {

    private final InMemoryDatabase db;
    private final int maxAttempts;
    private final Duration grace;
    private volatile Consumer<Integer> beforeCommit = attempt -> { };
    private final AtomicReference<RuntimeException> failNextCommit = new AtomicReference<>();

    public InMemoryUnitOfWork(InMemoryDatabase db) {
        this(db, 5, Duration.ofSeconds(30));
    }

    public InMemoryUnitOfWork(InMemoryDatabase db, int maxAttempts, Duration grace) {
        this.db = db;
        this.maxAttempts = maxAttempts;
        this.grace = grace;
    }

    /** TEST ONLY: runs after the function and before commit validation; receives the 1-based attempt number. */
    public void beforeCommit(Consumer<Integer> hook) {
        this.beforeCommit = hook;
    }

    /** TEST ONLY: the next commit throws this instead of applying anything. */
    public void failNextCommit(RuntimeException failure) {
        failNextCommit.set(failure);
    }

    @Override
    public <T> T execute(Function<TransactionContext, T> work) {
        String contendedListing = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Context tx = new Context();
            T result = work.apply(tx);
            beforeCommit.accept(attempt);
            if (tx.tryCommit()) {
                return result;
            }
            contendedListing = tx.firstListingId;
        }
        throw new ConcurrencyConflictException(contendedListing == null
                ? "Concurrent update, please retry"
                : "Concurrent update on listing " + contendedListing + ", please retry");
    }

    private final class Context implements TransactionContext {
        private final Map<String, Long> listingReads = new HashMap<>();
        private final Map<String, Long> offerReads = new HashMap<>();
        private final Map<String, Map<String, Long>> queryReads = new HashMap<>();
        private final List<Listing> listingWrites = new ArrayList<>();
        private final List<Offer> offerWrites = new ArrayList<>();
        private final List<OutboxMessage> outboxWrites = new ArrayList<>();
        private String firstListingId;

        @Override
        public Optional<Listing> findListing(String id) {
            checkNoWrites();
            var read = db.listing(id);
            listingReads.putIfAbsent(id, read == null ? 0 : read.version());
            if (firstListingId == null) {
                firstListingId = id;
            }
            return Optional.ofNullable(read).map(InMemoryDatabase.Versioned::value);
        }

        @Override
        public Optional<Offer> findOffer(String id) {
            checkNoWrites();
            var read = db.offer(id);
            offerReads.putIfAbsent(id, read == null ? 0 : read.version());
            return Optional.ofNullable(read).map(InMemoryDatabase.Versioned::value);
        }

        @Override
        public List<Offer> findOffersByListing(String listingId) {
            checkNoWrites();
            var read = db.offersOfListing(listingId);
            Map<String, Long> versions = new HashMap<>();
            read.forEach(v -> versions.put(v.value().id(), v.version()));
            queryReads.putIfAbsent(listingId, versions);
            return read.stream().map(InMemoryDatabase.Versioned::value)
                    .sorted(java.util.Comparator.comparing(Offer::createdAt)).toList();
        }

        @Override
        public void save(Listing listing) {
            listingWrites.add(listing);
        }

        @Override
        public void save(Offer offer) {
            offerWrites.add(offer);
        }

        @Override
        public void enqueue(OutboxMessage message) {
            outboxWrites.add(message);
        }

        private void checkNoWrites() {
            if (!listingWrites.isEmpty() || !offerWrites.isEmpty() || !outboxWrites.isEmpty()) {
                throw new IllegalStateException("reads must precede writes in a transaction");
            }
        }

        private boolean tryCommit() {
            db.lock.lock();
            try {
                RuntimeException injected = failNextCommit.getAndSet(null);
                if (injected != null) {
                    throw injected;
                }
                if (!readsStillCurrent()) {
                    return false;
                }
                listingWrites.forEach(db::putListing);
                offerWrites.forEach(db::putOffer);
                outboxWrites.forEach(m -> db.outbox.putIfAbsent(m.eventId(), new InMemoryOutboxStore.Entry(
                        m, m.createdAt().plus(grace), null, null, 0, null)));
                return true;
            } finally {
                db.lock.unlock();
            }
        }

        private boolean readsStillCurrent() {
            return listingReads.entrySet().stream().allMatch(e -> versionOf(db.listing(e.getKey())) == e.getValue())
                    && offerReads.entrySet().stream().allMatch(e -> versionOf(db.offer(e.getKey())) == e.getValue())
                    && queryReads.entrySet().stream()
                            .allMatch(e -> db.offerVersionsOfListing(e.getKey()).equals(e.getValue()));
        }

        private long versionOf(InMemoryDatabase.Versioned<?> v) {
            return v == null ? 0 : v.version();
        }
    }
}
