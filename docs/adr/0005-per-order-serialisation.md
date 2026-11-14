# 0005. Per-order serialisation of placement and cancellation

- **Status**: Accepted
- **Date**: 2026-11-14

## Context

ADR 0003 left an open point: `orders.order-placed` and `orders.order-cancelled` are different topics,
so Inventory can receive the cancellation of an order **before** its placement. Ignoring that early
cancellation (nothing to release yet) and reserving on the late placement would leave stock reserved
for an order that no longer exists, for ever.

Recording the early cancellation (a "marker") and checking it on placement fixes the sequential case,
but not the concurrent one. The two messages are consumed by different listener containers and can be
processed at the same moment. Under READ COMMITTED:

1. the placement checks for a marker: none;
2. the cancellation checks for a reservation: none, so it writes a marker;
3. the placement reserves stock and commits.

Both commit, and the order ends `RESERVED`. A test that disables the lock described below reproduces
this within 50 rounds (`PlaceCancelRaceIT`).

## Decision

1. **Cancellation markers.** When a cancellation finds no reservation, Inventory stores a marker
   (`cancellation_markers`, primary key `order_id`) in the same transaction as the processed event.
   A later placement of that order records `PLACEMENT_CANCELLED`, reserves nothing and publishes
   nothing.
2. **Per-order lock.** Both handlers take a transaction-scoped PostgreSQL advisory lock keyed by the
   order id (`pg_advisory_xact_lock`, the UUID folded to 64 bits) as the **first** statement of their
   transaction, before reading processed events, markers or reservations. The second transaction on the
   same order waits for the first to commit and then sees its marker or reservation. The lock is behind
   the application port `OrderLock`; its wait is bounded by `inventory.stock.lock-timeout`.
3. **Lock order.** Always the order lock first, then stock rows in ascending product id (ADR 0002). The
   two kinds of locks therefore never form a cycle.

Rejected alternatives: SERIALIZABLE isolation (every conflict becomes a retry, for every reservation,
and ADR 0002 assumes READ COMMITTED); a shared per-order registry table used only to get a lock; locking
the reservation row (it does not exist in the racy case).

## Consequences

- A cancelled order can never end holding stock, whatever the timing. Proven on a real database by
  `PlaceCancelRaceIT` (concurrent rounds and both forced orders).
- All work on one order is serialised; different orders run in parallel. An advisory-key collision
  only serialises two unrelated orders briefly.
- Markers are kept indefinitely (one small row per early cancellation); cleaning them up is a later
  concern.
- Every new handler that reads or changes the state of an order must take `OrderLock` first.
