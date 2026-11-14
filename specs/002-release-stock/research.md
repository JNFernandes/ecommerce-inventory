# Research: Release Reserved Stock When an Order Is Cancelled (US-02)

Phase 0 output for [plan.md](plan.md). Builds on US-01 ([001 research](../001-reserve-stock/research.md));
only new or changed decisions are listed. Constitution references are to v1.0.1.

## R1. Serialising placement and cancellation of the same order (race rule, FR-010 to FR-012)

- **Decision**: Both handlers (reserve and release) take a **transaction-scoped PostgreSQL advisory
  lock keyed by the order id** as the first statement of their transaction:
  `select pg_advisory_xact_lock(:key)`, with `key` = the order UUID folded to 64 bits
  (`mostSignificantBits ^ leastSignificantBits`). The lock is released automatically at commit or
  rollback. It is acquired before any read of `processed_events`, `cancellation_markers` or
  `reservations`, and before the stock row locks. The `lock_timeout` set for the transaction (R2 of
  US-01) also bounds this wait. The lock sits behind an application port `OrderLock`.
- **Rationale**: Placement and cancellation of one order arrive on different topics, so they can be
  processed at the same moment by different consumers. Each side checks something the other side
  writes (marker vs reservation). Under READ COMMITTED both could see "nothing" and the order would
  end Reserved for ever. Holding the same per-order lock makes the two transactions strictly ordered,
  so the second one always sees what the first committed. Lock order is always "order lock, then stock
  rows in productId order", so it cannot deadlock with stock locks. A hash collision between two order
  ids only serialises two unrelated orders briefly; it never breaks correctness.
- **Alternatives considered**:
  - **SERIALIZABLE isolation** for both transactions: correct, but every conflict becomes a
    serialization failure to retry, it would apply to every reservation, and the stock lock design of
    ADR 0002 assumes READ COMMITTED.
  - **A shared per-order table** both sides insert into (`order_registry(order_id PK)`): also correct
    (the second insert waits on the uncommitted unique key), but it adds a table only to get a lock.
  - **Locking the reservation row**: impossible when it does not exist yet, which is exactly the
    racy case.

## R2. Cancellation markers (FR-010, FR-011, FR-013)

- **Decision**: New table `cancellation_markers (order_id PK, event_id, created_at)`, a domain model
  `CancellationMarker` and a domain repository port `CancellationMarkerRepository` (`exists(OrderId)`,
  `save(CancellationMarker)`). The release handler writes a marker when it finds no reservation and no
  marker. The reserve handler (US-01) checks for a marker right after the idempotency check and, if one
  exists, records the event as `PLACEMENT_CANCELLED`, logs, creates no reservation and publishes
  nothing.
- **Rationale**: The primary key gives "at most one marker per order" (FR-013) for free; together with
  R1 the check-then-act is safe. Markers are tiny and kept indefinitely (spec assumption).
- **Alternatives considered**: a `CANCELLED_BEFORE_PLACEMENT` reservation row (would need a fake
  reservation id and items, and would break the "reservation = handled placement" meaning);
  publishing a rejection for the late placement (Orders already cancelled the order; spec says publish
  nothing).

## R3. Release decision in the domain (FR-005 to FR-009)

- **Decision**: `Reservation.release(Map<ProductId, StockItem> lockedStock, Instant now)` returns a
  `ReleaseOutcome` enum: `RELEASED`, `NOTHING_RESERVED` (status `REJECTED`), `ALREADY_RELEASED`. Only
  for `RELEASED` it adds every reserved quantity back with `StockItem.release(Quantity)` (overflow
  checked with `Math.addExact` against `Integer.MAX_VALUE`), moves the status to `RELEASED`, sets
  `releasedAt` and `updatedAt`, and raises `StockReleased`. A reserved product missing from
  `lockedStock` throws `StockItemMissingException` (domain exception): stock records are never
  deleted, so this is a broken invariant that goes through the US-01 retry and dead-letter path, never
  a partial release.
- **Rationale**: Keeps every rule (which statuses release, the transition, all-or-nothing) in the
  domain (Principle I) and unit-testable. The service only locks the reservation's products (ascending
  product id, ADR 0002) and saves.
- **Alternatives considered**: an SQL `update stock_items set available = available + n` per item
  (moves the rule to SQL, like ADR 0002 option B).

## R4. Storing a second outcome per reservation (FR-015, FR-016)

- **Decision** (migration `V3__release_stock.sql`):
  - Drop the unique constraint `uq_outcome_messages_reservation`; keep a plain index on
    `outcome_messages(reservation_id)`.
  - Add `processed_events.outcome_event_id uuid null references outcome_messages(event_id)` and
    backfill it for existing `RESERVATION_DECIDED` rows from `outcome_messages`.
  - Redelivery republishes the message referenced by `processed_events.outcome_event_id`, not "the"
    outcome of the reservation.
- **Rationale**: A reservation now has up to two outcomes (reserve, release). Linking the processed
  event to the exact message it produced removes any ambiguity and also covers future outcomes.
- **Alternatives considered**: a `kind` column with a unique `(reservation_id, kind)` (redelivery would
  still need to know which kind an event produced, which `processed_events` already encodes better).

## R5. processed_events for the new outcomes

- **Decision**: `ProcessedOutcome` gains `STOCK_RELEASED`, `CANCELLATION_IGNORED` (rejected or already
  released, or a duplicate early cancellation), `CANCELLATION_RECORDED` (marker written) and
  `PLACEMENT_CANCELLED` (placement ignored because of a marker). `processed_events.reservation_id`
  becomes nullable (no reservation exists for `CANCELLATION_RECORDED` and `PLACEMENT_CANCELLED`). The
  `ProcessedEvent` record holds `Optional<ReservationId>` and `Optional<UUID> outcomeEventId` so no
  accessor returns null (Principle V).
- **Rationale**: One idempotency table for both topics, as the spec requires (FR-014), with enough
  information to decide on redelivery whether to republish.

## R6. Shared publish-or-dead-letter step

- **Decision**: Move `publishOrDeadLetter` out of `ReserveStockService` into an application component
  `OutcomePublisher` (`publish(OutboundMessage, OrderId)`) used by both `ReserveStockService` and
  `ReleaseStockService`. Behaviour unchanged: publish synchronously; on `PublishFailedException` log
  and store in `outbound_dead_letters` in its own transaction; if that fails, log the full message.
- **Rationale**: FR-019/FR-020 forbid a second mechanism; one component guarantees both flows behave
  identically and keeps the services orchestration-only.

## R7. Consuming orders.order-cancelled

- **Decision**: New `OrderCancelledListener` (id `order-cancelled-listener`, `idIsGroup = false`, topic
  `${inventory.kafka.topics.order-cancelled}`) and `OrderCancelledMessageParser` reusing the strict
  reader settings of US-01 (shared base `StrictJsonParser`). DTO `OrderCancelledMessage(eventId,
  occurredAt, aggregateId, version, customerId, reason)`; `customerId` and `reason` optional; version
  must be 1. Invalid → `InvalidMessageException` → existing error handler (not retried, dead-lettered).
  The listener is started by the existing `KafkaListenerStarter`; the existing `DefaultErrorHandler`
  applies to every container of the factory, so no new retry or dead-letter wiring (FR-020).
- **Rationale**: Same consumer group `inventory-service`; a second listener on a different topic in
  the same group is standard. Same ack mode (`manual_immediate`) and offset rules.

## R8. inventory.stock-released message

- **Decision**: Record `StockReleasedMessage(eventId, occurredAt, aggregateId, version, orderId,
  items[ReleasedItem(productId, quantity long)], releasedAt)`; new case in
  `JsonOutboundMessageFactory`'s exhaustive switch; topic from `inventory.kafka.topics.stock-released`
  (`KAFKA_TOPIC_STOCK_RELEASED`, default `inventory.stock-released`).
- **Rationale**: Mirrors `StockReservedMessage`; field order = JSON order = `events.md`.

## R9. Contract and ADR updates

- **Decision**:
  - `ecommerce-infra/docs/events.md` on a new branch `chore/US-02-inventory-release-events` created
    from `main` **after** the US-01 contract PR is merged: mark the Inventory consumption of
    `orders.order-cancelled` and `inventory.stock-released` as implemented and state that items are the
    consolidated reserved quantities.
  - **ADR 0005** "Per-order serialisation of placement and cancellation" records R1/R2, and ADR 0003's
    open point is marked resolved by 0005.
- **Rationale**: FR-018 requires the contract update; ADR 0003 explicitly asked for a follow-up
  decision.

## R10. Tests

- **Decision**:
  - Unit: `Reservation.release` (all three outcomes, consolidated quantities, missing stock),
    `StockItem.release`, `ReleaseStockService` (every branch, publish failure via `OutcomePublisher`),
    `ReserveStockService` marker branch, `OutcomePublisher`, parser, listener, factory.
  - Integration: V3 migration (constraints, backfill), marker and reservation update adapters,
    `PostgresOrderLock` (second transaction waits), and a concurrency IT that runs placement and
    cancellation of the same order together many times in both start orders and asserts the order
    never ends `RESERVED` and stock is restored (SC-003, SC-004).
  - Component: release end to end; rejected and already-released ignored; cancel-before-place; redelivery
    republishes the same `eventId`; invalid cancellation dead-lettered; publish failure dead-lettered.
  - `KafkaTestContainer` creates the two new topics; the component base waits for both listeners.
