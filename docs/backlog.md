# Backlog — product ideas to run through `/feature`

Each item is phrased as a business need on purpose: turning it into a spec is the `product-planner`'s job.

## 1. Accepting an offer must be all-or-nothing  ← start here

Sellers have reported a listing showing two accepted offers. Today `acceptOffer` does several separate writes
and then publishes, so two concurrent accepts can both succeed, and a crash between the writes and the publish
loses the `offer.accepted` event.

Expected direction: a Firestore transaction for the state change, plus a transactional outbox (events written in
the same transaction and relayed to Pub/Sub). The in-memory adapter must keep the same semantics so tests stay
meaningful.

## 2. Offers expire after 48 hours

Buyers don't want their money committed forever. Pending offers older than 48h become EXPIRED and buyers are
notified. Consider Cloud Scheduler → an authenticated endpoint vs. checking expiry lazily on read.

## 3. Sellers can counter-offer

A seller can reply to an offer with a counter-amount. The buyer can accept it, which sells the listing, or
decline. Each side gets a notification event.

## 4. Price-drop alerts for watchers

Buyers can watch a listing. When the seller lowers the price, watchers are notified through a new event type
on the existing topic. Mind the fan-out cost.

## 5. Ops: Cloud Armor in front of the API

Add an external HTTPS load balancer with a serverless NEG and a Cloud Armor policy (rate limiting + OWASP
preconfigured rules). Infrastructure only: `release-engineer` prepares it and a human applies it.
