# 0003. Inventory takes part in the order saga in parallel with Payments

- **Status**: Accepted
- **Date**: 2026-11-13
- **Amended**: 2026-11-14 — open point resolved by ADR 0005; the decision is unchanged.

## Context

The order saga is choreographed (ecommerce-orders ADR 0003): there is no central orchestrator, and
each service reacts to the others' events. Until now it involved only Orders and Payments: Orders
confirmed an order as soon as the payment succeeded.

An order must not be confirmed if its products are not in stock. Inventory could join the saga in
two ways:

- **Sequentially**: for example, Inventory reserves first and Payments charges only after
  `StockReserved` (or the reverse). This adds latency and couples Payments to Inventory events.
- **In parallel**: Payments and Inventory both react to `OrderPlaced` independently, and Orders
  combines the two outcomes.

The shared contracts and the saga diagram live in `ecommerce-infra/docs/events.md`.

## Decision

Inventory participates **in parallel with Payments**:

1. Orders publishes `OrderPlaced` (`orders.order-placed`).
2. Inventory consumes it, reserves all items atomically (ADR 0002) and publishes `StockReserved`
   (`inventory.stock-reserved`) or `StockRejected` (`inventory.stock-rejected`). Independently,
   Payments publishes `PaymentProcessed` or `PaymentFailed`.
3. Orders waits for **both** outcomes. It confirms the order only when the payment was processed
   **and** the stock was reserved. If either fails, it cancels the order and publishes
   `OrderCancelled` (`orders.order-cancelled`).
4. Compensation: on `OrderCancelled`, Inventory releases a `RESERVED` reservation and publishes
   `StockReleased` (`inventory.stock-released`), and Payments refunds a processed payment. Each
   ignores the event when there is nothing to undo.

Inventory events identify the order by `orderId`; their `aggregateId` is the reservation id.
Inventory never calls Orders or Payments directly.

## Consequences

- Stock reservation adds no latency to the payment path; an order takes as long as the slower of
  the two branches.
- Orders must track two outcomes per order and handle them arriving in either order, possibly
  duplicated (at-least-once delivery). That work belongs to Orders. Until the Inventory stories
  ship, Orders keeps confirming on `PaymentProcessed` alone.
- Some orders will be charged and then refunded (payment succeeded, stock rejected), or have stock
  reserved and then released (payment failed). This is the accepted cost of running the branches
  in parallel.
- Release is idempotent: releasing an order that was never reserved, was rejected or is already
  released is a logged no-op (constitution Principle IV).
- **Open point for the release story:** `orders.order-placed` and `orders.order-cancelled` are
  different topics, so Inventory can receive the cancellation **before** the placement. Ignoring
  that early cancellation and then reserving on the late `OrderPlaced` would leave stock reserved
  for a cancelled order. **Resolved by ADR 0005**: an early cancellation stores a marker, a later
  placement of that order reserves nothing, and a per-order lock makes this hold even when both
  messages are processed at the same moment.
