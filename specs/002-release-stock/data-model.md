# Data Model: Release Reserved Stock When an Order Is Cancelled (US-02)

Phase 1 output for [plan.md](plan.md). Only additions and changes to the US-01 model
([001 data model](../001-reserve-stock/data-model.md)) are listed.

## 1. Domain (`com.ecommerce.inventory.domain`)

### Enums

| Enum | Change |
|---|---|
| `ReservationStatus` | add `RELEASED` |
| `ReleaseOutcome` (new) | `RELEASED`, `NOTHING_RESERVED` (reservation was `REJECTED`), `ALREADY_RELEASED` |

### `StockItem` (changed)

- `release(Quantity q)`: `availableQuantity += q` with `Math.addExact`; a result above
  `Integer.MAX_VALUE` throws `InvalidQuantityException` (cannot happen with real data: a release only
  returns what was taken).

### `Reservation` (changed)

| Field | Change |
|---|---|
| `status` | now mutable: `RESERVED → RELEASED` only |
| `releasedAt` | new, `Optional<Instant>`, present only when `RELEASED` |
| `updatedAt` | set to the release time on release |

Behaviour:

```text
ReleaseOutcome release(Map<ProductId, StockItem> lockedStock, Instant now)
  REJECTED  -> NOTHING_RESERVED   (no change, no event)
  RELEASED  -> ALREADY_RELEASED   (no change, no event)
  RESERVED  -> every item: lockedStock[productId].release(quantity)
               (missing product -> StockItemMissingException, nothing changed)
               status = RELEASED, releasedAt = updatedAt = now
               raise StockReleased(reservationId, orderId, items, now)
               -> RELEASED
```

`Reservation.restoreReleased(...)` rebuilds a released reservation (no events). Invariant enforced in
the constructor: `releasedAt` present ⇔ status `RELEASED`.

### State transitions

```text
(none) --decide: all available--> RESERVED --release--> RELEASED
(none) --decide: any failure----> REJECTED            (release -> NOTHING_RESERVED, no change)
                                  RELEASED --release--> ALREADY_RELEASED, no change
```

### `CancellationMarker` (new)

| Field | Type | Notes |
|---|---|---|
| `orderId` | `OrderId` | at most one marker per order |
| `eventId` | `UUID` | the order-cancelled event that created it |
| `createdAt` | `Instant` | |

### Events

- `StockReleased(ReservationId reservationId, OrderId orderId, List<RequestedItem> items, Instant
  releasedAt)`; added to the `permits` of `InventoryDomainEvent`.

### Exceptions

- `StockItemMissingException` (new, extends `InventoryDomainException`): a reserved product has no
  stock record at release time.

### Repository ports

| Port | Change |
|---|---|
| `ReservationRepository` | add `void update(Reservation)` (status, `released_at`, `updated_at`) |
| `CancellationMarkerRepository` (new) | `boolean exists(OrderId)`, `void save(CancellationMarker)` |

## 2. Application (`com.ecommerce.inventory.application`)

| Type | Kind | Change |
|---|---|---|
| `ReleaseStockCommand` | record, new | `UUID eventId`, `String topic`, `OrderId orderId` |
| `ReleaseStockOutcome` | sealed, new | `Released(ReservationId)`, `Ignored(Optional<ReservationId>, String reason)`, `CancellationRecorded`, `Redelivered(ReservationId)` |
| `ReleaseStockService` | service, new | lock order → idempotency → reservation or marker → `Reservation.release` → save in one transaction → publish via `OutcomePublisher` |
| `ReserveStockOutcome` | changed | add `PlacementCancelled` |
| `ReserveStockService` | changed | lock order first; after the idempotency check, a marker → `PLACEMENT_CANCELLED`, nothing reserved or published; redelivery republishes by `outcomeEventId` |
| `OutcomePublisher` | component, new | publish; on failure outbound dead letter in its own transaction (moved from `ReserveStockService`) |
| `port/OrderLock` | port, new | `void lock(OrderId)`: serialises all work on one order until the transaction ends |
| `port/ProcessedOutcome` | changed | add `STOCK_RELEASED`, `CANCELLATION_IGNORED`, `CANCELLATION_RECORDED`, `PLACEMENT_CANCELLED` |
| `port/ProcessedEvent` | changed | `Optional<ReservationId> reservationId`, new `Optional<UUID> outcomeEventId` |
| `port/OutcomeMessageRepository` | changed | `findByReservationId` replaced by `findByEventId(UUID)` |

## 3. Persistence (`V3__release_stock.sql`)

### `reservations` (changed)

| Change | Detail |
|---|---|
| status check | `status in ('RESERVED','REJECTED','RELEASED')` |
| new column | `released_at timestamptz null` |
| new check | `(status = 'RELEASED') = (released_at is not null)` |

### `outcome_messages` (changed)

- drop `uq_outcome_messages_reservation`; add index `ix_outcome_messages_reservation (reservation_id)`.

### `processed_events` (changed)

| Change | Detail |
|---|---|
| `reservation_id` | now nullable |
| new column | `outcome_event_id uuid null references outcome_messages(event_id)` |
| backfill | `outcome_event_id` from `outcome_messages` for existing `RESERVATION_DECIDED` rows |
| outcome check | adds `STOCK_RELEASED`, `CANCELLATION_IGNORED`, `CANCELLATION_RECORDED`, `PLACEMENT_CANCELLED` |
| new check | `outcome_event_id is not null` ⇔ outcome in (`RESERVATION_DECIDED`, `STOCK_RELEASED`) |

### `cancellation_markers` (new)

| Column | Type | Constraints |
|---|---|---|
| `order_id` | `uuid` | PK |
| `event_id` | `uuid` | `NOT NULL` |
| `created_at` | `timestamptz` | `NOT NULL` |

No change to `stock_items`, `reservation_items`, `outbound_dead_letters`, `consumed_dead_letters`.

## 4. What each cancellation writes (one transaction, after the order lock)

| Situation | stock_items | reservations | cancellation_markers | outcome_messages | processed_events.outcome | Published |
|---|---|---|---|---|---|---|
| Reserved | + reserved quantities | → RELEASED | — | + StockReleased | `STOCK_RELEASED` | stock-released |
| Rejected | — | — | — | — | `CANCELLATION_IGNORED` | nothing |
| Already released | — | — | — | — | `CANCELLATION_IGNORED` | nothing |
| No reservation, no marker | — | — | + marker | — | `CANCELLATION_RECORDED` | nothing |
| No reservation, marker exists | — | — | — | — | `CANCELLATION_IGNORED` | nothing |
| Same `eventId` again | — | — | — | — | — | the stored message, only if `STOCK_RELEASED` |

And for a placement whose order has a marker: nothing but `processed_events` (`PLACEMENT_CANCELLED`).
