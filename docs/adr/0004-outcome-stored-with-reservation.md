# 0004. Outcome message stored with the reservation, published after commit

- **Status**: Accepted
- **Date**: 2026-11-13

## Context

The constitution (Principle III) requires that a reservation is saved before its outcome is
published, and that the consumer offset is committed only after the save. Kafka and PostgreSQL do
not share a transaction, so three failure windows exist:

1. **The publish fails after the commit.** The reservation exists, but nobody hears about it.
2. **The service stops between the commit and the publish.** The offset is not committed, so the
   same `orders.order-placed` message is delivered again. Its `eventId` is already in
   `processed_events`, so a naive idempotency check would skip it and the outcome would never be
   published.
3. **The same order arrives again with a new `eventId`.** Orders may retry, or a message may be
   duplicated upstream.

Consumers downstream (Orders, Notifications) deduplicate by `eventId`, so publishing the *same*
message twice is harmless. Publishing a *different* outcome for the same order is not.

## Decision

1. In the reservation transaction we also save the exact outcome message that will be published
   (event id, topic, key, JSON payload) in `outcome_messages`, together with the stock changes, the
   reservation and the `processed_events` record. The domain never sees this: the message is stored
   through the application port `OutcomeMessageRepository`.
2. After the transaction commits, the service publishes the message synchronously, and only then
   does the listener acknowledge the offset.
3. If the publish fails, the message is written to `outbound_dead_letters` (`PENDING`, retry count 0,
   next retry time, last error) **in its own transaction**. The reservation is never rolled back.
   Redelivery from that table is the US-03 retry worker.
4. If an inbound `eventId` was already processed, nothing changes and the stored outcome message is
   **republished verbatim, with its original `eventId`**. This closes failure window 2 at the cost of
   an occasional duplicate that consumers discard.
5. If the order already has a reservation but the `eventId` is new, the event is recorded as
   `DUPLICATE_ORDER_IGNORED`, logged, and nothing is published (window 3).

We do not use a transactional outbox relay for every message: publishing directly after the commit
keeps latency low (SC-001), and the dead-letter table covers the failure case.

## Consequences

- No outcome is lost: it is either published, stored for the retry worker, or republished on
  redelivery.
- Delivery is at-least-once. Consumers must deduplicate by `eventId`, which the shared contract in
  `ecommerce-infra/docs/events.md` already requires.
- The stored payload is `jsonb`, so PostgreSQL may reorder its fields. A republish is the same JSON
  object, not necessarily the same bytes; consumers parse JSON, so this is acceptable.
- One extra row per reservation (`outcome_messages`).
- US-02 (release) and US-03 (retry worker) follow the same pattern: store the outcome with the
  state change, publish after commit, dead-letter on failure, republish on redelivery.
