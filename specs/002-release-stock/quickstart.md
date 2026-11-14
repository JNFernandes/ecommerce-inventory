# Quickstart: Validate US-02 Release Stock

Contracts: [contracts/kafka-events.md](contracts/kafka-events.md). Tables: [data-model.md](data-model.md).
Prerequisites and helper functions are the same as US-01 ([001 quickstart](../001-reserve-stock/quickstart.md));
the app runs with `.\mvnw.cmd spring-boot:run` against ecommerce-infra.

## 1. Automated validation

```powershell
.\mvnw.cmd -B -q compile; .\mvnw.cmd -B -q checkstyle:check; .\mvnw.cmd -B -q spotless:check; .\mvnw.cmd -B verify
```

| Spec reference | Test |
|---|---|
| Story 1, FR-005, FR-009 | `Reservation.release` unit tests; component test release end to end |
| Story 2, FR-006 to FR-008 | unit + component: rejected and already released are ignored |
| Story 3, FR-010 to FR-012, SC-003 | component: cancel before place; concurrency IT: place and cancel together, both start orders, many rounds |
| Story 4, FR-014, FR-015 | unit + component: same cancellation twice republishes the same `eventId`, stock restored once |
| Story 5, FR-019 to FR-021 | component: invalid cancellation dead-lettered; publish failure → `outbound_dead_letters` |
| SC-004 | concurrency IT: releases and reservations of the same products, stock never negative |
| SC-005 | factory and component tests assert the exact `stock-released` fields and key |

## 2. Manual scenarios against ecommerce-infra

Send cancellations to `orders.order-cancelled` keyed by the order id (kafka-ui, or the US-01 helper
`Send-Raw` with the topic changed). Message shape: see the contracts file.

| # | Steps | Expected |
|---|---|---|
| A | Place 3 × SKU-003, then cancel it | `inventory.stock-released` with SKU-003 quantity 3; stock back to its previous value; reservation `RELEASED` |
| B | Place 1 × SKU-005 (rejected), then cancel it | nothing published; log "nothing reserved"; `processed_events` `CANCELLATION_IGNORED` |
| C | Cancel order A again with a new `eventId` | nothing published; stock unchanged |
| D | Cancel a new order X, then place X with 1 × SKU-003 | after the cancel: a row in `cancellation_markers`; after the place: no reservation, nothing published, SKU-003 unchanged, `PLACEMENT_CANCELLED` |
| E | Send the cancellation of A again with its original `eventId` | the same `stock-released` message (same `eventId`) appears again; stock unchanged |
| F | Send `not json` to `orders.order-cancelled`, then a valid cancellation | a `consumed_dead_letters` row for `orders.order-cancelled`, attempts 1; the valid one is processed |
