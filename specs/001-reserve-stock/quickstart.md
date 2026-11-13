# Quickstart: Validate US-01 Reserve Stock

How to prove the feature works end to end. Contracts: [contracts/kafka-events.md](contracts/kafka-events.md).
Tables: [data-model.md](data-model.md).

## Prerequisites

- JDK 21 on `JAVA_HOME`, Docker running
- `ecommerce-infra` started: `docker compose up -d` in `../ecommerce-infra` (PostgreSQL
  `localhost:5433`, Kafka `localhost:9092`, kafka-ui `http://localhost:8080`, topics created)

## 1. Automated validation (quality gate)

```powershell
.\mvnw.cmd -B -q compile
.\mvnw.cmd -B -q checkstyle:check
.\mvnw.cmd -B -q spotless:check
.\mvnw.cmd -B verify
```

Expected: all pass. `verify` covers, among others:

| Spec reference | Test |
|---|---|
| Story 1, Story 2, FR-004 to FR-008 | domain unit tests for `Reservation.decide` and `RequestedItems.consolidate` |
| Story 3, FR-010, FR-011, SC-002 | concurrency integration test: 20 parallel orders for SKU with 5 units; opposite-order deadlock test |
| FR-009, FR-013 | migration integration tests: negative stock and second reservation per order are refused by the database |
| Story 4, FR-014 to FR-016, SC-004 | application unit tests and a component test that redelivers the same message |
| Story 5, FR-023, FR-024, SC-006, SC-007 | component tests: malformed message then valid message; publisher forced to fail |
| SC-005 | component tests assert the exact JSON fields, key = `aggregateId` = reservation id |

## 2. Manual run against ecommerce-infra

Start the service with the `local` profile (seeds SKU-001..SKU-005):

```powershell
.\mvnw.cmd spring-boot:run
```

Check the seed:

```bash
docker exec -it ecommerce-postgres-inventory-1 psql -U inventory_user -d inventory \
  -c "select product_id, available_quantity from stock_items order by product_id"
```

Expected: SKU-001 10, SKU-002 25, SKU-003 100, SKU-004 5, SKU-005 0.

### Scenario A: reservation

Place an order for 2 × SKU-003 through the Orders service (Postman: `POST` to Orders' place-order
endpoint), or produce the message directly in kafka-ui (Topics → `orders.order-placed` → Produce
Message, key = the `aggregateId`) using the example in the contracts file with `SKU-003`.

Expected within 2 seconds:

- `inventory.stock-reserved` has a message whose `orderId` is the order id and whose key equals its
  `aggregateId`.
- `stock_items`: SKU-003 = 98.
- `reservations`: one `RESERVED` row for the order; `outcome_messages`: one row.

### Scenario B: rejection

Place an order for 1 × SKU-005 and 1 × SKU-999.

Expected: `inventory.stock-rejected` with reason `unknown product and insufficient stock` and two
failing items (SKU-005 requested 1 available 0; SKU-999 requested 1 available 0). No stock change.

### Scenario C: redelivery and duplicate order

- Produce the exact message of Scenario A again (same `eventId`): stock unchanged; the original
  `inventory.stock-reserved` message is published once more with the **same** `eventId`.
- Produce it with a new `eventId` but the same `aggregateId`: stock unchanged, nothing published,
  a log line "Duplicate OrderPlaced ignored" with the order id, the new event id and the existing
  reservation id.

### Scenario D: invalid message

Produce `{"not":"an order"}` to `orders.order-placed`, then a valid order.

Expected: `consumed_dead_letters` has a row for the invalid message (attempts 1); the valid order
is processed normally.

### Scenario E: Kafka down after commit

Not practical manually; covered by the component test that forces the publisher to fail and checks
`outbound_dead_letters` (status `PENDING`, `retry_count` 0).

## 3. Reset local data

```bash
docker exec -it ecommerce-postgres-inventory-1 psql -U inventory_user -d inventory \
  -c "truncate reservations, outcome_messages, reservation_items, processed_events cascade; update stock_items set available_quantity = v.q from (values ('SKU-001',10),('SKU-002',25),('SKU-003',100),('SKU-004',5),('SKU-005',0)) v(p,q) where product_id = v.p"
```
