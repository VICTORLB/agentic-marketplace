# Spec: Accepting an offer is all-or-nothing (transaction + transactional outbox)

- Backlog: `docs/backlog.md`, item 1
- Branch: `feat/atomic-offer-accept`
- Status: APPROVED (human answers recorded under "Open questions"). Delivery is split in 4 PRs; this round ships PR 1 only.

## 1. Business need

Sellers must never see a listing with more than one accepted offer, and the winning buyer must always get notified.
We will know it worked when (a) no listing in Firestore has more than one `ACCEPTED` offer, and (b) every `ACCEPTED`
offer has exactly one `offer.accepted` outbox entry that reaches `published` state. Consumers may receive that
event more than once, and they handle the duplicate as a no-op.

## 2. Current behaviour (what the code does today)

`MarketplaceService.acceptOffer` (`src/main/java/com/vx/marketplace/application/MarketplaceService.java:86`):

1. reads the listing and the offer outside any transaction,
2. `listings.save(sold)`, then `offers.save(accepted)`, then one `offers.save(rejected)` per other pending offer.
   These are separate Firestore writes.
3. `events.publish(OfferAccepted)` synchronously to Pub/Sub.

Failure modes:
- **Race:** two accepts both read `ACTIVE` before either writes, so both succeed. The result is two `ACCEPTED`
  offers, and the listing is written as `SOLD` twice.
- **Partial write:** a crash or timeout between writes leaves, for example, a `SOLD` listing with no `ACCEPTED`
  offer.
- **Lost event:** a crash after the writes and before or during `publish` loses `offer.accepted` permanently. If
  the publish fails, the client gets a 500 even though the state already changed.
- **Retry UX:** a client retrying after a timeout gets a 409 (`Offer ... is ACCEPTED, expected PENDING`), even when
  its first call succeeded.

## 3. Scope

In scope:
- `acceptOffer` becomes one atomic unit: listing to `SOLD`, chosen offer to `ACCEPTED`, every other pending offer to
  `REJECTED`, and one outbox entry for `offer.accepted`. All of these commit together or nothing does.
- A transactional outbox plus a relay to Pub/Sub, with an inline fast path and a periodic sweeper for recovery.
- Consumers de-duplicate by a stable business `eventId`, because the relay can publish twice.
- In-memory adapters with the same atomicity and conflict semantics, so the service tests stay meaningful.

Out of scope (follow-ups, see Open questions Q6 and Q7):
- Moving `createListing` and `placeOffer` onto the outbox. They keep publishing directly in this spec, but they
  gain an `eventId` attribute.
- Making `placeOffer` transactional against a concurrent accept.
- Repairing listings that already have two accepted offers.

## 4. Acceptance criteria

Unless stated otherwise, each criterion must hold for both the in-memory adapters (default profile, all tests) and the
Firestore adapters (gcp profile, contract test against the emulator, see Q5).
"Outbox entry" means a persisted `OutboxMessage` (section 5.2).

### Happy path

**AC-1: Accept is applied atomically and records exactly one event.**
Given an ACTIVE listing L owned by seller S with PENDING offers A (buyer-1) and B (buyer-2)
When S calls `POST /api/listings/L/offers/A/accept`
Then the response is `200` with offer A in status `ACCEPTED`
And listing L is `SOLD`, offer B is `REJECTED`
And exactly one outbox entry exists with type `offer.accepted`, aggregateId L, and payload fields
`offerId=A, listingId=L, buyerId=buyer-1, sellerId=S, amountCents, occurredAt`.

**AC-2: The inline relay publishes after commit.**
Given AC-1 succeeded and Pub/Sub is healthy
When the request finishes
Then the event has been published exactly once with attributes `type=offer.accepted`, `aggregateId=L` and
`eventId=<outbox entry id>`
And the outbox entry is marked published (`publishedAt` set, no longer due for relay).

**AC-3: The wire format stays backward compatible.**
Given an `offer.accepted` event relayed from the outbox
Then its JSON `data` has the same fields and field names that `PubSubEventPublisher` produces today for
`DomainEvent.OfferAccepted`
And the existing attributes `type` and `aggregateId` are unchanged. The only addition is the `eventId` attribute.

### Concurrency

**AC-4: Concurrent accepts of different offers produce exactly one winner.**
Given an ACTIVE listing with PENDING offers A and B
When the seller accepts A and B concurrently
Then exactly one call returns `200` and the other returns `409`
And the final state has exactly one `ACCEPTED` offer, all other offers `REJECTED`, and the listing `SOLD`
And exactly one `offer.accepted` outbox entry exists.
Test with two methods. (a) Deterministic: the in-memory unit of work's `beforeCommit` hook commits the competing
accept during the first attempt. (b) Stress: 8 threads released by a `CountDownLatch`, each accepting a different
offer, repeated 50 times. In every repetition exactly one call succeeds.

**AC-5: Concurrent accepts of the same offer (double click) both succeed and record one event.**
Given an ACTIVE listing with PENDING offer A
When the seller accepts A twice concurrently
Then both calls return `200` with A `ACCEPTED`
And exactly one `offer.accepted` outbox entry exists.

**AC-6: A concurrent write during a transaction triggers a re-run, not a lost update.**
Given a transaction T1 that has read listing L
When another transaction commits a change to L, or to any offer T1 read (including adding a new offer to L), before
T1 commits
Then T1's commit is rejected, its function is re-run against fresh state, and the outcome reflects the fresh state.

**AC-7: Persistent contention ends in a clean conflict.**
Given every attempt of the unit of work conflicts (in memory: the `beforeCommit` hook always mutates L)
When `maxAttempts` (default 5) is exhausted
Then `ConcurrencyConflictException` is thrown, which maps to `409` with detail
`"Concurrent update on listing L, please retry"`
And no partial state and no outbox entry are left behind.

### Idempotency and retries

**AC-8: Retrying an accept is idempotent.**
Given offer A was already accepted on listing L (L is `SOLD`)
When the seller calls accept for A again
Then the response is `200` with A `ACCEPTED`
And no new outbox entry is created and nothing new is published.
(This replaces the current test `cannotAcceptTwice`, which expects a 409.)

**AC-9: Accepting a different offer on a sold listing is a conflict.**
Given listing L is `SOLD` with offer A accepted
When the seller accepts offer B, or any offer not in `PENDING` state
Then the response is `409` and no state changes and no outbox entry is created.

**AC-10: A timeout that hides a successful commit is safe to retry.**
Given the commit succeeded but the client saw an error, for example the adapter's 10s await timed out
When the client retries the same accept
Then AC-8 applies: `200` and no duplicate event.

### Atomicity and failure

**AC-11: A failure before commit leaves no trace.**
Given the in-memory unit of work is configured with `failNextCommit(new IllegalStateException("boom"))`
When the seller accepts A
Then the call fails (`500` over HTTP)
And listing L is still `ACTIVE`, all offers are still `PENDING`, and no outbox entry exists
And the same accept succeeds when called again afterwards.

**AC-12: An exception inside the transaction function rolls back.**
Given the transaction function throws a domain exception after it has buffered writes
Then none of the buffered writes are applied, in both adapters.

**AC-13: A failed publish does not fail the request and does not lose the event.**
Given the commit succeeded and the `EventPublisher` throws during the inline relay
When the request completes
Then the response is still `200`. The failure is logged with the eventId and no payload.
And the outbox entry stays unpublished, with `attempts` incremented and `lastError` recorded (truncated to
500 chars).

**AC-14: The sweeper recovers unpublished entries.**
Given an unpublished outbox entry whose `relayAfter` is in the past. This happens when the process crashed after
commit, or after AC-13.
When `OutboxRelay.relayDue()` runs
Then the entry is published with the same `eventId` and marked published.

**AC-15: The sweeper leaves fresh entries to the inline path.**
Given an entry created less than `grace` ago (default 30s)
When `relayDue()` runs
Then that entry is not published.

**AC-16: One failure does not block the rest of the batch.**
Given three due entries, where publishing the second one fails
When `relayDue()` runs
Then the first and third entries are published and marked
And the second stays due again after `lease` (default 60s), with `attempts` incremented
And the result reports `published=2, failed=1`.

**AC-17: Concurrent sweepers do not double-claim.**
Given two `relayDue()` calls running concurrently over the same due entries
Then each entry is claimed by at most one of them within a lease window, because the claim happens inside a
transaction.

**AC-18: Each sweep has bounded work.**
When `relayDue()` runs
Then it processes at most `batchSize` entries (default 100) and stops starting new publishes after `timeBudget`
(default 20s), so the call fits inside the Cloud Run 30s request timeout.

### Consumers (at-least-once)

**AC-19: Duplicates are detected by eventId.**
Given two Pub/Sub deliveries with different `messageId`s and the same `eventId` attribute
When both reach `/pubsub/push`
Then the first is `HANDLED` and the second is `DUPLICATE`. Both are acked with `204`.

**AC-20: Messages without eventId fall back to messageId.**
Given a delivery without an `eventId` attribute, for example published before this change
Then de-duplication uses `messageId`, exactly as it does today.

### Authorization and validation

**AC-21: Only the listing's seller can accept.**
Given caller X is not the listing's seller
When X accepts any offer on L, including an offer id that does not exist
Then the response is `403`, and no state changes and no outbox entry is created.
The existing `ListingControllerTest.nonSellerCannotAccept` must still pass.

**AC-22: Not-found checks run in a fixed order.**
- Unknown listing returns `404`.
- Then, if the caller is not the seller, `403`.
- Then, if the offer is unknown or belongs to another listing, `404`.
All three checks run inside the transaction, against transactional reads.

**AC-23: The Firestore adapter preserves domain exceptions.**
Given the gcp adapters
When the transaction function throws `NotFoundException`, `ForbiddenException`, `BusinessRuleException` or
`IllegalArgumentException`
Then the same exception type reaches `ApiExceptionHandler`, and the response is `404/403/409/400`, never `500`
because of `Futures.await` wrapping it in `IllegalStateException`.

**AC-24: Reading after writing inside a transaction is rejected.**
Given code inside a unit of work that calls a read after a `save` or `enqueue`
Then `IllegalStateException("reads must precede writes in a transaction")` is thrown, in both adapters. This
mirrors the Firestore rule, so in-memory tests cannot pass code that fails on Firestore.

**AC-25: The relay endpoint is authenticated.**
Given the gcp profile with `marketplace.outbox.relay.verify=true`
When `POST /internal/outbox/relay` is called without a valid OIDC token for the configured audience and service
account
Then the response is `401` and nothing is relayed
And with a valid token the response is `200` with body `{"published": n, "failed": m}`.
In the default profile, verification is off, the same as for `/pubsub/push`.

### Retention and operations

**AC-26: Published entries expire.**
Given an entry is marked published at time t
Then its `expireAt` is `t + retention` (default `P7D`, from env var `OUTBOX_RETENTION`), so a Firestore TTL policy
can delete it. Unpublished entries never have `expireAt`.

**AC-27: All configuration comes from env vars.**
`grace`, `lease`, `batchSize`, `timeBudget`, `retention`, the relay audience and the relay service account are all
bound from environment variables with the defaults above. Nothing runs on background threads. Graceful shutdown
stays under 10s.

## 5. Design

### 5.1 Domain (`domain`, framework-free)

- `Offer`, `Listing`, `DomainEvent`: no field changes.
- New business rule as a pure function, so it is unit-testable without adapters:
  `Listing.acceptOffer(Offer chosen, List<Offer> allOffersOnListing)` returns an `AcceptOutcome` record with
  `sold`, `accepted`, `rejected` and `alreadyAccepted`. Rules:
  - If `chosen` is already `ACCEPTED` and the listing is `SOLD`, return `alreadyAccepted=true` with no changes
    (AC-8).
  - If the listing is not `ACTIVE`, or `chosen` is not `PENDING`, throw `BusinessRuleException` (AC-9).
  - Otherwise return the sold listing, the accepted offer, and every other `PENDING` offer rejected.
  The current `markSold()`, `accept()` and `reject()` invariants stay as they are.

### 5.2 Application ports (`application/port`, framework-free)

```java
/** Runs work atomically. The function may run more than once on contention, so it must have no side effects. */
public interface UnitOfWork {
    <T> T execute(Function<TransactionContext, T> work); // throws ConcurrencyConflictException when attempts run out
}

/** Reads must precede writes (AC-24). Writes are buffered and applied at commit. */
public interface TransactionContext {
    Optional<Listing> findListing(String id);
    Optional<Offer> findOffer(String id);
    List<Offer> findOffersByListing(String listingId);
    void save(Listing listing);
    void save(Offer offer);
    void enqueue(OutboxMessage message);
}

public record OutboxMessage(String eventId, DomainEvent event, Instant createdAt) { }

public interface OutboxStore {
    /** Atomically claims up to `limit` unpublished entries whose relayAfter <= now, pushing relayAfter to now+lease. */
    List<OutboxMessage> claimDue(Instant now, Duration lease, int limit);
    void markPublished(String eventId, Instant publishedAt, Instant expireAt);  // idempotent
    void recordFailure(String eventId, String error);                            // attempts++, lastError
}

public class ConcurrencyConflictException extends BusinessRuleException { }  // so existing mapping gives 409
```

Changed port: `EventPublisher.publish(DomainEvent event)` becomes `publish(String eventId, DomainEvent event)`.
`createListing` and `placeOffer` pass `ids.get()`.

When an entry is enqueued, the unit of work sets `relayAfter = createdAt + grace`. The adapter does this, with the
`grace` value from config.

### 5.3 Application use cases

- `MarketplaceService.acceptOffer(listingId, offerId, sellerId)`:
  1. Run `uow.execute(tx -> ...)`. Inside the function, do all reads first: listing (404), owner check (403),
     offer and listing match (404), all offers on the listing. Then call `Listing.acceptOffer(...)`. If
     `alreadyAccepted` is true, return without writing. Otherwise save the listing and offers and enqueue
     `OutboxMessage(ids.get(), OfferAccepted(...), clock.instant())`. Event ids are generated inside the function.
     That is safe because only the committed attempt's id persists.
  2. After the commit, if a message was enqueued, call `relay.relayNow(message)`. This is best effort and never
     throws (AC-13).
  3. Return the accepted offer.
  The constructor gains `UnitOfWork` and `OutboxRelay`. `ApplicationConfig` wires them.
- New `OutboxRelay` (application, framework-free):
  - `relayNow(OutboxMessage m)`: calls `publisher.publish(m.eventId(), m.event())`, then `markPublished`. On an
    exception it logs and calls `recordFailure`, and both steps swallow exceptions.
  - `RelayResult relayDue()`: calls `claimDue(now, lease, batchSize)` and publishes each claimed entry in
    `createdAt` order. It stops starting new publishes once `timeBudget` has elapsed (AC-18).
  - Config record: `OutboxSettings(Duration grace, Duration lease, int batchSize, Duration timeBudget,
    Duration retention)`.
- `OfferEventHandler.handle(messageId, eventId, eventType, payload)`: the de-duplication key is `eventId` when it is
  present and not blank, otherwise `messageId` (AC-19, AC-20). `PubSubPushController` passes
  `attributes.get("eventId")`.

### 5.4 In-memory adapters (`adapter/out/memory`)

- New `InMemoryDatabase`: versioned maps for listings, offers and outbox entries, plus one commit lock.
  `InMemoryListingRepository`, `InMemoryOfferRepository`, `InMemoryUnitOfWork` and `InMemoryOutboxStore` all take
  it in their constructors, so non-transactional reads (`getListing`, `listOffers`) see committed transactional
  writes. Non-transactional `save` bumps the version under the same lock. Update `LocalAdaptersConfig` and the
  test setup.
- `InMemoryUnitOfWork` uses optimistic concurrency, so it shows the same outcomes Firestore shows:
  - The context records the version of every document it reads, and the id-plus-version set of every query
    result.
  - Writes are buffered. A read after a write throws (AC-24).
  - At commit, under the lock, it re-validates the read versions and re-runs the queries. A changed document or a
    phantom offer is a conflict, so the function is re-run, up to `maxAttempts` (constructor argument, default 5).
    When attempts run out it throws `ConcurrencyConflictException`.
  - The buffered writes are applied all at once, or not at all.
  - Test hooks, package-visible or public but documented as test-only: `beforeCommit(Consumer<Integer> attempt)`
    for deterministic conflict injection, and `failNextCommit(RuntimeException)` for AC-11.
- `LoggingEventPublisher`: records `(eventId, event)` and gains `failNext(RuntimeException)` for AC-13 and AC-16.

### 5.5 GCP adapters (`adapter/out/gcp`)

- `FirestoreUnitOfWork`: `db.runTransaction(tx -> ..., TransactionOptions.createReadWriteOptionsBuilder()
  .setNumberOfAttempts(maxAttempts).build())`. The context wraps `tx.get(DocumentReference)` and `tx.get(Query)`
  for `whereEqualTo("listingId", id)`, which uses the existing single-field index. It buffers writes and flushes
  them with `tx.set(...)` when the function returns.
  - Unwrap `ExecutionException` causes and rethrow `RuntimeException` causes as they are (AC-23).
  - Map `ABORTED` after retries to `ConcurrencyConflictException` (AC-7).
  - Keep the 10s bounded await (AC-10).
  - Verify every API against `google-cloud-libraries-bom` 26.50.0. This is part of the reviewer's hallucination
    check.
- Pull the document mappers out of `FirestoreListingRepository` and `FirestoreOfferRepository` into package-private
  static `toDoc` and `fromSnapshot` methods. The repositories and the unit of work then share one mapping.
- `FirestoreOutboxStore`, collection `outbox`, document id = `eventId`. Fields:
  - `type`, `aggregateId`
  - `payload`: JSON string, serialized with the app `ObjectMapper` when enqueued, so the relay publishes exactly
    the stored bytes (AC-3)
  - `createdAt`: `Timestamp`
  - `relayAfter`: `Timestamp`, present only while unpublished
  - `publishedAt`: `Timestamp`
  - `expireAt`: `Timestamp`, set only when published
  - `attempts`: long
  - `lastError`: string

  Use Firestore `Timestamp`, not `Instant.toString()`. ISO strings with varying fraction lengths do not sort
  correctly, and TTL requires a Timestamp field.
  - `claimDue`: one transaction that queries `whereLessThanOrEqualTo("relayAfter", now).orderBy("relayAfter")
    .limit(n)` and updates `relayAfter = now + lease`. This uses a single-field range query, so no composite
    index is needed, because published entries drop the field.
  - `markPublished`: `update(publishedAt, expireAt, relayAfter = FieldValue.delete())`.
  - Deserialization back into `DomainEvent` uses a type-to-record-class map covering the 3 existing types. An
    unknown type is logged and marked failed. It is never dropped silently.
- `PubSubEventPublisher.publish(eventId, event)` adds the attribute `eventId`.
- Relay endpoint `POST /internal/outbox/relay` lives in `adapter/in/web/OutboxRelayController`. When verification
  is on, it checks the token with a second `PushTokenVerifier` bean, configured with `RELAY_AUDIENCE` and
  `RELAY_SERVICE_ACCOUNT`. If verification is on but the bean is missing, it fails closed with `401`.
  - It calls `OutboxRelay.relayDue()` synchronously.
  - It does not take an `Idempotency-Key`. The operation is naturally idempotent because it is claim-based, and
    calling it twice is safe.
- Config: `application.yml` and `application-gcp.yml` get `marketplace.outbox.*` and `marketplace.outbox.relay.*`.
  `deploy.yml` env_vars get the new variables.

### 5.6 API and event contract changes

| Surface | Change | Compatible? |
|---|---|---|
| `POST .../offers/{offerId}/accept` | A repeated accept of the already-accepted offer returns `200` instead of `409`. A new `409` covers contention that exhausts retries. | Yes for clients. Behaviour is more lenient. |
| Pub/Sub message attributes | New `eventId` attribute on every event | Additive |
| Pub/Sub `data` JSON | Unchanged | Yes |
| New endpoint | `POST /internal/outbox/relay` (OIDC) | New |
| Firestore | New `outbox` collection | New. No migration of existing data. |

## 6. Implementation plan (one PR per step, `main` deployable after each)

Estimated total is about 900 changed lines, so this is split into 4 PRs, each under 300 lines. Each step starts
with the listed failing test.

1. **Ports + in-memory unit of work and outbox (no behaviour change).**
   - Add the `UnitOfWork`, `TransactionContext`, `OutboxMessage`, `OutboxStore` and `ConcurrencyConflictException`
     ports.
   - Add `InMemoryDatabase`, `InMemoryUnitOfWork` and `InMemoryOutboxStore`, and refactor the in-memory repos onto
     the shared database.
   - Add the domain rule `Listing.acceptOffer`.
   - Write first: `InMemoryUnitOfWorkTest`, covering
     - `rollsBackAllWritesWhenFunctionThrows` (AC-12)
     - `rerunsFunctionWhenReadDocumentChangesBeforeCommit` (AC-6)
     - `detectsPhantomOfferAddedToQueriedListing` (AC-6)
     - `throwsConcurrencyConflictAfterMaxAttempts` (AC-7)
     - `rejectsReadAfterWrite` (AC-24)

     Also `ListingTest.acceptOffer*` for the AC-8 and AC-9 rules, and `InMemoryOutboxStoreTest` for claim, lease,
     grace and expireAt (AC-15, AC-17, AC-26).
   - Write the unit-of-work tests as an abstract `UnitOfWorkContractTest` so step 2 can reuse them.
2. **Firestore unit of work and outbox store (wired, not yet used).**
   - Add `FirestoreUnitOfWork` and `FirestoreOutboxStore`, extract the shared mappers, and add the beans in
     `GcpAdaptersConfig`.
   - Write first: `FirestoreUnitOfWorkContractTest` and `FirestoreOutboxStoreContractTest`, extending the step 1
     contract tests and annotated `@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")`.
   - Also a plain unit test for exception unwrapping (AC-23) that needs no emulator.
3. **`acceptOffer` through the unit of work, the outbox and the inline relay, plus de-duplication by eventId.**
   This is the behaviour change.
   - Change the `EventPublisher` signature, add `OutboxRelay.relayNow`, add the `eventId` attribute, change
     `OfferEventHandler` and `PubSubPushController`, and update `ApplicationConfig`.
   - Write first, in `MarketplaceServiceTest.AcceptOffer`:
     - `concurrentAcceptsOfDifferentOffersHaveExactlyOneWinner` (AC-4, deterministic and stress)
     - `concurrentAcceptsOfSameOfferBothSucceedWithOneEvent` (AC-5)
     - `retryingAcceptReturnsSameOfferWithoutNewEvent` (AC-8, replaces `cannotAcceptTwice`)
     - `failedCommitLeavesListingAndOffersUntouched` (AC-11)
     - `publishFailureStillSucceedsAndKeepsEntryUnpublished` (AC-13)
     - `acceptedEventIsPublishedOnceWithEventIdAndMarkedPublished` (AC-1, AC-2)

     Also `OfferEventHandlerTest.deduplicatesByEventIdAcrossDifferentMessageIds` and
     `fallsBackToMessageIdWithoutEventId` (AC-19, AC-20), and a JSON-shape assertion for AC-3 in a
     `PubSubEventPublisherTest` that uses a captured `PubsubMessage`.
   - Events that fail to publish after this deploy stay in the outbox until step 4 ships. They are delayed, not
     lost.
4. **Sweeper, relay endpoint and config.**
   - Add `OutboxRelay.relayDue`, `OutboxRelayController`, the second verifier bean, the `marketplace.outbox.*`
     properties and the deploy env vars.
   - Add a commented `scripts/gcp-schedule-relay.sh` for the human to run. It creates the Scheduler job, the relay
     SA and the TTL policy. IAM stays human-owned.
   - Write first:
     - `OutboxRelayTest.recoversUnpublishedEntryAfterGrace` (AC-14)
     - `skipsEntriesWithinGrace` (AC-15)
     - `continuesBatchWhenOnePublishFails` (AC-16)
     - `stopsAtBatchSizeAndTimeBudget` (AC-18, with a fake `Clock`)
     - `OutboxRelayControllerTest.rejectsMissingToken` and `relaysWithValidToken` (AC-25)

## 7. Risks

- **Firestore query locking and phantoms.** Server SDK transactions lock the documents they read. The serialization
  point that matters is the listing document, which every accept reads and writes, so two accepts always conflict.
  A concurrent `placeOffer` is not transactional, though. It can still insert a `PENDING` offer on a listing that
  has just been sold. This is an existing gap and is out of scope here (Q7). The in-memory adapter detects
  phantoms, which is stricter than Firestore might be.
- **Transaction write limit.** One Firestore transaction allows at most 500 writes, so a listing with more than
  about 498 pending offers cannot be accepted. This is acceptable for now. Log a clear error.
- **Duplicates by design.** The relay can publish the same eventId twice: a crash after publish but before
  `markPublished`, or an inline publish slower than `grace`. AC-19 makes this harmless only for this service's own
  consumer. Any external subscriber must also de-duplicate by `eventId`. Document this in the PR.
- **Cost.**
  - Each accept adds 1 outbox write and 1 publish-mark update.
  - The sweeper, if it runs every minute, costs about 43k Firestore reads a month, because an empty query still
    bills one read. TTL deletes are billed as deletes.
  - Cloud Scheduler allows 3 free jobs per billing account.
  - All of this is negligible at current volume.
- **Rolling deploy.** Old and new revisions overlap briefly. Old revisions publish without `eventId`, and AC-20
  covers that. No data migration is needed.
- **Existing corrupted data.** Listings that already have two `ACCEPTED` offers stay as they are. Calling accept on
  them returns `409` under AC-9, or `200` under AC-8 for either already-accepted offer.

## 8. Open questions for the human (answered)

Human decisions, recorded verbatim in short form below. The original questions follow for context.

- **Q1:** Natural (result-based) idempotency for accept is accepted. It is a documented exception to the CLAUDE.md
  `Idempotency-Key` rule.
- **Q2:** Cloud Scheduler calling the authenticated relay endpoint. Infra and IAM are not in this round; the human
  applies them later.
- **Q3:** 7-day retention after publish via TTL. Alerting on stuck entries is a follow-up.
- **Q4:** No ordering keys.
- **Q5:** Yes, run the Firestore emulator in CI, added in PR 2 together with the Firestore contract tests.
- **Q6 and Q7:** Yes, each as a separate follow-up backlog item (see `docs/backlog.md`).
- **Q8:** No report script for now.
- **Q9:** 4-PR split. This round ships PR 1 only.

### Original questions


1. **Q1, accept idempotency.** CLAUDE.md says that "any write a client can retry takes an `Idempotency-Key`".
   The spec instead proposes *natural* idempotency for accept: re-accepting the already-accepted offer returns 200
   and emits nothing. The outcome is fully determined by `(listingId, offerId)`, and the change needs no API
   change; `smoke.sh` and existing clients send no key. Do you accept that, or do you want a required
   `Idempotency-Key` header on accept? A required header is a breaking change, so it would need a 400-on-missing
   rollout plan.
2. **Q2, relay trigger on Cloud Run.** The proposal is an inline publish after commit, plus a Cloud Scheduler job
   calling `POST /internal/outbox/relay` with OIDC every minute. Worst-case recovery latency is then about
   grace + 1 minute. The alternatives are:
   - (b) an in-process `@Scheduled` poller, which needs `--no-cpu-throttling` and `--min-instances=1`. This means
     instance-based billing at an always-on cost.
   - (c) an Eventarc Firestore trigger on `outbox` document creation. This adds infra and IAM.

   Which do you choose? Do you also approve creating the relay service account, its `roles/run.invoker`-style
   access, and the Scheduler job? These are IAM and billing changes that you own.
3. **Q3, outbox retention.** The default is 7 days after publish, through a Firestore TTL policy on `expireAt`.
   The policy is created with `gcloud firestore fields ttls update`, which is human-applied. Is 7 days right? Should
   unpublished entries alert after N attempts? For example, a log-based alert on `attempts >= 10`, or moving the
   entry to a "dead" state.
4. **Q4, ordering.** The spec makes no ordering guarantee. Consumers must not rely on order, and the sweeper
   relays in `relayAfter` order on a best-effort basis. Do you want Pub/Sub ordering keys by `aggregateId`? Those
   require enabling ordering on the publisher and recreating `offer-events-push` with `--enable-message-ordering`.
   A stalled key also blocks later events for that listing.
5. **Q5, Firestore emulator in CI.** The Firestore contract tests (AC-6, AC-7, AC-17, AC-23 and AC-24 on gcp) only
   run when `FIRESTORE_EMULATOR_HOST` is set. Should `ci.yml` start the emulator, using
   `gcloud beta emulators firestore start` or the emulator Docker image, so those criteria are enforced on every PR?
   If not, the Definition of Done is only met for the in-memory side.
6. **Q6, other writes.** Should `createListing` and `placeOffer` move onto the outbox in a follow-up spec, so there
   is one publish path and `EventPublisher` is used only by the relay? They have the same lost-event window today.
   The recommendation is yes, as a separate backlog item.
7. **Q7, `placeOffer` vs accept race.** Should a follow-up make `placeOffer` transactional, reading the listing
   document in the same transaction, so that no `PENDING` offer can land on a listing that was just sold?
8. **Q8, existing bad data.** Do you want a one-off read-only report script to list listings with more than one
   `ACCEPTED` offer, so they can be fixed manually? It would not be part of these PRs.
9. **Q9, PR split.** Is the 4-PR split in section 6 acceptable, with every PR targeting `main` and stacked on this
   branch? Or do you prefer one feature branch merged once? The latter would be about 900 lines and break the
   <300-line guideline.
