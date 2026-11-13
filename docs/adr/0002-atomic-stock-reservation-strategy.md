# 0002. Atomic stock reservation: pessimistic row locks ordered by productId

- **Status**: Accepted
- **Date**: 2026-11-13

## Context

When `orders.order-placed` arrives, Inventory must reserve every item of the order or none of them
(constitution Principle IV). Several consumers (one per partition, across several instances) can
process orders for the same products at the same time. The strategy must:

- never let available stock go negative, and never lose an update;
- be all-or-nothing across items: a rejected order changes no stock;
- not deadlock when two orders touch overlapping products listed in different orders;
- report, on rejection, which items failed with their `requested` and `available` quantities
  (`inventory.stock-rejected` payload in `ecommerce-infra/docs/events.md`), and reject the whole
  order for an unknown product;
- keep the business decision in the domain, not in SQL (Principle I).

Two candidates were compared. Optimistic locking with `@Version` was also considered and discarded:
on hot products it turns contention into retry storms, and every retry re-runs the whole use case.

**A. Pessimistic row locks ordered by productId.** In one transaction:
`SELECT ... FROM stock_items WHERE product_id IN (:ids) ORDER BY product_id FOR UPDATE`, map the
rows to `StockItem` aggregates, let the domain decide, then save the new quantities and the
reservation.

**B. Conditional update per item.** In one transaction, for each item in productId order:
`UPDATE stock_items SET available_quantity = available_quantity - :n
WHERE product_id = :p AND available_quantity >= :n`; if any update affects 0 rows, roll back.

| Concern | A. Ordered `FOR UPDATE` | B. Conditional `UPDATE` |
|---|---|---|
| No negative stock / no lost update | Yes (rows locked before reading) | Yes (atomic check-and-set) |
| All-or-nothing | Domain decides before any write | Needs a rollback after partial writes |
| Deadlock avoidance | Locks taken in productId order | Also needs productId order |
| Rejection details (`available`) | Already loaded | Extra query after the rollback |
| Unknown product | Missing row in the result | 0 rows updated, indistinguishable from insufficient stock without another query |
| Where the business rule lives | Domain (`StockItem`, `Reservation`) | SQL `WHERE` clause |
| Recording the rejection | Same transaction as the processed-event record | The rollback discards it; a second transaction is needed |
| Round trips | 1 select + 1 batched update | 1 update per item |
| Lock hold time | From the select until commit (short) | From the first update until commit (short) |

B is slightly faster per order, but it moves the stock rule into SQL, needs extra queries and a
second transaction on the rejection path, and still depends on the same lock ordering.

## Decision

We will use **A: pessimistic row locks acquired in ascending `productId` order**.

- The repository exposes a single method that loads the stock items for a set of product ids with
  `PESSIMISTIC_WRITE`, ordered by `productId`. In PostgreSQL the row-locking step runs after the
  sort, so locks are taken in that order. Callers never lock stock rows one by one.
- The application service runs load → domain decision → save of stock, reservation and
  processed-event record in one transaction. The domain returns either a `RESERVED` reservation or
  a `REJECTED` one with the failing items; on rejection no stock row is modified.
- Release (`orders.order-cancelled`) locks the reservation's stock rows the same way, in the same
  order.
- A `lock_timeout` is set for these transactions so a stuck lock fails fast and goes through the
  consumer retry/backoff path instead of blocking a partition indefinitely.
- The database `CHECK (available_quantity >= 0)` remains as a safety net.

## Consequences

- The whole decision, including all-or-nothing, unknown products and the rejection details, stays
  in the domain and is unit-testable without a database.
- A consistent lock order across reserve and release prevents deadlocks between overlapping
  orders. Any new code path that locks stock rows MUST use the same ordered method.
- Orders for the same hot product serialise on that row. This is intended; locks are held only for
  one short transaction.
- Behaviour under concurrency must be proven by integration tests against real PostgreSQL
  (Testcontainers): parallel reservations for the same products never oversell, and overlapping
  orders listing items in opposite order complete without deadlock.
- If profiling later shows lock contention as a bottleneck, B can be revisited in a new ADR that
  supersedes this one.
