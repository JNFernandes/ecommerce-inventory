# Feature Specification: Reserve Stock When an Order Is Placed (US-01)

**Feature Branch**: `feature/US-01-reserve-stock`

**Created**: 2026-11-13

**Status**: Draft

**Input**: User description: "US-01: Reserve stock when an order is placed. Inventory consumes
`orders.order-placed` and tries to reserve stock for the whole order, then publishes
`inventory.stock-reserved` or `inventory.stock-rejected`. All-or-nothing, consolidated quantities,
rejection with failing items, no negative stock, no overselling under concurrency, one reservation
per order, idempotent by eventId, database first then publish then offset, outbound and inbound
dead-letter handling, local seed data SKU-001 to SKU-005. Out of scope: release (US-02), retry
worker (US-03), HTTP API, restocking, prices."

## Context

Inventory takes part in the order saga in parallel with Payments (ADR 0003). When Orders announces
a new order, Inventory decides whether the whole order can be supplied from stock and tells the
rest of the system the outcome. Orders will later confirm an order only if both the payment and
the stock reservation succeeded.

The message contracts (topics, envelope, payload fields) are defined in
`ecommerce-infra/docs/events.md`, which is the source of truth. Relevant contracts:

- **Consumed — order placed** (`orders.order-placed`): envelope (`eventId`, `occurredAt`,
  `aggregateId` = order id, `version`) plus `customerId`, `items` (`productId`, `quantity`,
  `unitPrice`) and `totalAmount`.
- **Produced — stock reserved** (`inventory.stock-reserved`): envelope (`aggregateId` =
  reservation id) plus `orderId`, `items` (`productId`, `quantity`) and `reservedAt`.
- **Produced — stock rejected** (`inventory.stock-rejected`): envelope (`aggregateId` =
  reservation id) plus `orderId`, `reason`, `items` (`productId`, `requested`, `available`) and
  `rejectedAt`.

## User Scenarios & Testing *(mandatory)*

The "users" of this feature are the other services in the saga (Orders, and indirectly the
customer whose order is confirmed or cancelled) and the developers and operators who run it.

### User Story 1 - Reserve all items of an order that can be fully supplied (Priority: P1)

When an order is placed and every product in it is known and has enough available stock, Inventory
sets the requested quantities aside for that order, so they cannot be sold to anyone else, and
announces that the stock is reserved.

**Why this priority**: This is the core value of the service: without it, Orders can confirm
orders that cannot be shipped. It is the happy path of the saga.

**Independent Test**: With stock seeded for a few products, publish one order-placed message whose
items are all available, then verify that available stock decreased by exactly the ordered
quantities, a reservation in status Reserved exists for that order, and exactly one stock-reserved
message for that order was published.

**Acceptance Scenarios**:

1. **Given** SKU-001 has 10 available and SKU-002 has 25 available, **When** an order is placed
   for 3 × SKU-001 and 5 × SKU-002, **Then** SKU-001 has 7 and SKU-002 has 20 available, a
   Reserved reservation exists for the order with those two items, and one stock-reserved message
   is published with the order id, both items and their quantities, and the reservation time.
2. **Given** SKU-004 has 5 available, **When** an order is placed for exactly 5 × SKU-004,
   **Then** the reservation succeeds and SKU-004 has 0 available (stock may reach zero, never
   below).
3. **Given** SKU-001 has 10 available, **When** an order lists SKU-001 twice, with quantities 2 and
   3, **Then** the quantities are consolidated, 5 units are reserved, the reservation and the
   published message contain a single SKU-001 line with quantity 5, and SKU-001 has 5 available.
4. **Given** a successful reservation, **When** the published stock-reserved message is inspected,
   **Then** its message key and `aggregateId` are the reservation id, its `orderId` is the order id
   from the consumed message, and it carries a new `eventId`, `occurredAt` and `version` 1.

---

### User Story 2 - Reject an order that cannot be fully supplied, without touching stock (Priority: P1)

When at least one product of an order is unknown or does not have enough available stock,
Inventory reserves nothing for that order, leaves all stock unchanged, records the rejection and
announces it with the reason and the list of failing items.

**Why this priority**: All-or-nothing is a non-negotiable business rule; a partial reservation
would lock stock for an order that will be cancelled. Rejection is also what lets Orders cancel
the order quickly. It is as critical as Story 1.

**Independent Test**: With stock seeded, publish an order-placed message where one item exceeds
available stock (or references an unknown product), then verify that no stock changed, a Rejected
reservation exists for the order, and exactly one stock-rejected message lists the failing items
with requested and available quantities.

**Acceptance Scenarios**:

1. **Given** SKU-001 has 10 and SKU-004 has 5 available, **When** an order is placed for 2 ×
   SKU-001 and 6 × SKU-004, **Then** no stock changes (SKU-001 stays 10, SKU-004 stays 5), a
   Rejected reservation exists for the order, and one stock-rejected message is published with
   reason `insufficient stock` and a single failing item `SKU-004` with requested 6 and available
   5. SKU-001 is not listed because it could have been supplied.
2. **Given** SKU-005 has 0 available, **When** an order is placed for 1 × SKU-005, **Then** the
   order is rejected with reason `insufficient stock` and failing item `SKU-005`, requested 1,
   available 0.
3. **Given** no product `SKU-999` exists, **When** an order is placed for 1 × SKU-001 and 2 ×
   SKU-999, **Then** no stock changes and the order is rejected with reason `unknown product` and
   failing item `SKU-999`, requested 2, available 0.
4. **Given** SKU-004 has 5 available and `SKU-999` does not exist, **When** an order is placed
   for 6 × SKU-004 and 1 × SKU-999, **Then** the order is rejected with reason
   `unknown product and insufficient stock` and both failing items are listed (SKU-004: requested
   6, available 5; SKU-999: requested 1, available 0).
5. **Given** SKU-001 has 10 available, **When** an order lists SKU-001 twice with quantities 6 and
   7, **Then** the consolidated quantity 13 is checked, the order is rejected, and the failing
   item is SKU-001 with requested 13 and available 10.

---

### User Story 3 - Never oversell under concurrent orders (Priority: P1)

When several orders compete for the same products at the same moment, Inventory processes them so
that the total reserved never exceeds what was available. Some orders succeed, the rest are
rejected, and no order waits forever because of another.

**Why this priority**: Overselling is the main failure an inventory service exists to prevent.
The service runs several consumers in parallel, so concurrency is the normal case, not an edge
case.

**Independent Test**: Seed a product with a small quantity, deliver many orders for it at the same
time (through several partitions and consumers), then verify that the number of successful
reservations multiplied by the ordered quantity never exceeds the starting stock, stock is never
negative, and every order received exactly one outcome.

**Acceptance Scenarios**:

1. **Given** SKU-004 has 5 available, **When** 20 orders of 1 × SKU-004 are processed
   concurrently, **Then** exactly 5 are Reserved, 15 are Rejected with `insufficient stock`,
   SKU-004 ends with 0 available, and 20 outcome messages are published (one per order).
2. **Given** SKU-001 and SKU-002 have plenty of stock, **When** one order lists SKU-001 then
   SKU-002 and a concurrent order lists SKU-002 then SKU-001, **Then** both orders complete with
   an outcome (no deadlock or indefinite wait) and stock reflects both reservations.

---

### User Story 4 - Process each order exactly once despite redeliveries (Priority: P2)

Messages can be delivered more than once (at-least-once delivery), and Orders could in principle
announce the same order twice. Inventory handles each order once: a redelivered message changes
nothing, and a second announcement of an already handled order is ignored.

**Why this priority**: Without idempotency, redeliveries would reserve stock twice for one order.
It is required for correctness but is only exercised in failure or retry situations, so it ranks
just after the core flows.

**Independent Test**: Publish the same order-placed message twice (same `eventId`) and verify stock
was reduced once and only one reservation exists; then publish a different message (new `eventId`)
for the same order and verify nothing changes and nothing new is published.

**Acceptance Scenarios**:

1. **Given** an order-placed message was already processed (stock reserved, outcome published),
   **When** the same message (same `eventId`) is delivered again, **Then** stock and the
   reservation are unchanged, and at most the original outcome message is published once more,
   with the same `eventId` as the first time, so downstream consumers can discard it.
2. **Given** an order already has a reservation (Reserved or Rejected), **When** another
   order-placed message with a different `eventId` arrives for the same order id, **Then** it is
   ignored, a log line records the order id, the new event id and the existing reservation id,
   no stock or reservation changes, and nothing is published.

---

### User Story 5 - Keep the flow moving when messaging fails (Priority: P2)

Failures on either side of the service must neither lose outcomes nor block other orders. If the
outcome cannot be published after the reservation was saved, it is kept for later redelivery. If
an incoming message cannot be processed, it is retried and, if it still fails (or is malformed),
set aside so that the following orders keep flowing.

**Why this priority**: These paths protect the saga from stalling and from silently losing
outcomes, but they are only hit when something goes wrong.

**Independent Test**: (a) Make publishing fail after a successful reservation and verify the
outcome is stored as a pending outbound dead-letter entry while the reservation stays saved.
(b) Deliver a malformed message followed by a valid one and verify the malformed one is stored in
the inbound dead-letter store and the valid one is processed normally.

**Acceptance Scenarios**:

1. **Given** a reservation was saved, **When** publishing its outcome message fails, **Then** the
   reservation and stock changes stay committed, the failure is logged with the topic, key, event
   id, order id and cause, and the full outcome message is stored as an outbound dead-letter entry
   with status Pending, retry count 0, a next-retry time and the last error.
2. **Given** a message that is not valid JSON, or that lacks required fields (for example no
   `eventId`, no items, a non-positive quantity or a blank product id), **When** it is consumed,
   **Then** it is not retried, nothing is reserved, it is stored as an inbound dead-letter entry
   with the raw content, topic, partition, offset, error and time, the offset is committed and the
   next message on the partition is processed.
3. **Given** a valid message whose processing fails because of a temporary problem (for example
   the database is briefly unavailable), **When** it is consumed, **Then** it is retried with
   increasing delays, and if it eventually succeeds the normal outcome is produced exactly once.
4. **Given** a valid message whose processing keeps failing, **When** all retries are exhausted,
   **Then** it is stored as an inbound dead-letter entry with the last error and number of
   attempts, the failure is logged with full context, the offset is committed, and the following
   messages on that partition are processed.

---

### User Story 6 - Try the flow locally with sample products (Priority: P3)

A developer running the service against the local infrastructure finds a set of sample products
already in stock, documented in the README, so they can place orders end-to-end (for example from
Postman through the Orders service) and observe reservations and rejections.

**Why this priority**: Developer convenience; it does not affect production behaviour.

**Independent Test**: Start the local infrastructure and the service, then confirm the five sample
products exist with the documented quantities and that an order for SKU-005 is rejected while an
order for SKU-003 is reserved.

**Acceptance Scenarios**:

1. **Given** a fresh local database, **When** the service starts in the local environment,
   **Then** these products exist: SKU-001 (10), SKU-002 (25), SKU-003 (100), SKU-004 (5),
   SKU-005 (0), and the README lists them.
2. **Given** the service runs in any non-local environment (including automated tests and
   production), **When** it starts, **Then** no sample products are created.
3. **Given** the sample products were already created and stock has changed, **When** the service
   restarts locally, **Then** the sample data is not duplicated and quantities are not reset.

---

### Edge Cases

- **Exact stock**: ordering exactly the available quantity succeeds and leaves 0 available.
- **Zero stock**: a product with 0 available is known; ordering it is `insufficient stock`, not
  `unknown product`.
- **Duplicate lines**: lines for the same product are consolidated before any check; requested
  quantities in the reservation and in the published messages are the consolidated totals.
- **Mixed failures**: when unknown and insufficient products appear in the same order, both kinds
  are listed and the reason is `unknown product and insufficient stock`.
- **Product id matching**: product ids are matched exactly (case-sensitive); `sku-001` is not
  `SKU-001` and is an unknown product.
- **Very large quantity**: a quantity above the available stock (including values far above it) is
  rejected as insufficient, never causes negative stock or an arithmetic error.
- **Unsupported version**: a message with a `version` other than 1 is treated as invalid and goes
  to the inbound dead-letter store.
- **Extra fields**: unknown additional fields in a consumed message are ignored (additive changes
  keep the version).
- **Same order, different event**: a second order-placed for an order already handled is ignored
  even if its items differ.
- **Crash after save, before publish**: on redelivery of the same message, the stored outcome is
  republished with its original `eventId` (see Story 4, scenario 1), so no outcome is lost.
- **Publish failure and dead-letter store failure**: if storing the outbound dead-letter entry also
  fails, the error is logged with the full outcome message so it can be recovered manually; the
  reservation remains committed.
- **Message broker unavailable at startup**: the service starts and stays healthy; orders are
  processed once the broker becomes reachable.

## Requirements *(mandatory)*

### Functional Requirements

**Consuming**

- **FR-001**: The system MUST consume order-placed messages from `orders.order-placed` and use the
  envelope `aggregateId` as the order id.
- **FR-002**: The system MUST validate each consumed message before processing: present `eventId`
  (UUID), `occurredAt` (timestamp), `aggregateId` (UUID), `version` equal to 1, and a non-empty
  `items` list where every item has a non-blank `productId` and a `quantity` greater than 0.
  `customerId`, `unitPrice` and `totalAmount` are not used by this feature and MUST NOT cause a
  rejection or failure when present.
- **FR-003**: A message that cannot be parsed or fails validation MUST NOT be retried or partially
  processed; it MUST be stored as an inbound dead-letter entry and its offset committed.

**Reservation decision**

- **FR-004**: The system MUST consolidate items that share a `productId` by summing their
  quantities before checking or reserving stock.
- **FR-005**: The system MUST reserve stock for an order only if every consolidated item refers to
  a known product whose available quantity is at least the requested quantity; in that case it
  MUST decrease each product's available quantity by the requested quantity.
- **FR-006**: If any consolidated item refers to an unknown product or exceeds the available
  quantity, the system MUST reject the whole order and MUST NOT change any product's stock.
- **FR-007**: The rejection MUST list every failing item (and only failing items) with its
  `productId`, `requested` (consolidated quantity) and `available` quantity at decision time,
  using 0 as `available` for an unknown product.
- **FR-008**: The rejection reason MUST be exactly one of `insufficient stock` (only insufficient
  items), `unknown product` (only unknown items), or `unknown product and insufficient stock`
  (both kinds present).
- **FR-009**: Available stock MUST never become negative. This MUST be guaranteed both by the
  reservation rules and by a storage-level constraint that refuses any negative value.

**Concurrency**

- **FR-010**: Concurrently processed orders MUST NOT reserve more units of a product than were
  available (no overselling, no lost updates).
- **FR-011**: Concurrent orders sharing products MUST always complete, regardless of the order in
  which they list their items; products MUST be claimed in a deterministic order (ascending
  `productId`) so that orders cannot block each other indefinitely.

**Reservation record**

- **FR-012**: Every handled order MUST be stored as exactly one reservation with: reservation id
  (generated by Inventory), order id, status (Reserved or Rejected), the consolidated items with
  requested quantities, the rejection reason and failing items when Rejected, and creation and
  last-update timestamps.
- **FR-013**: At most one reservation MUST exist per order id. This MUST be guaranteed both by the
  processing rules and by a storage-level uniqueness rule.
- **FR-014**: When an order-placed message with a new `eventId` arrives for an order that already
  has a reservation, the system MUST ignore it, log the order id, the new event id and the
  existing reservation id, change nothing and publish nothing.

**Idempotency and write flow**

- **FR-015**: The system MUST record each processed inbound `eventId`. A message whose `eventId`
  was already processed MUST NOT change stock or reservations.
- **FR-016**: For a redelivered message (already processed `eventId`), the system MUST republish
  the original outcome message with its original `eventId` and payload, at most once per
  redelivery, so that an outcome is never lost if the service stopped between saving and
  publishing.
- **FR-017**: The stock changes, the reservation, the processed-event record and the outcome
  message to be published MUST be saved together atomically: either all are saved or none is.
- **FR-018**: The outcome message MUST be published only after that save has succeeded.
- **FR-019**: The consumed message's offset MUST be committed only after the save has succeeded
  (or after the message has been stored as an inbound dead-letter entry).

**Publishing**

- **FR-020**: On success the system MUST publish one message to `inventory.stock-reserved` with
  `orderId`, `items` (`productId`, consolidated `quantity`) and `reservedAt`.
- **FR-021**: On rejection the system MUST publish one message to `inventory.stock-rejected` with
  `orderId`, `reason`, `items` (`productId`, `requested`, `available`) and `rejectedAt`.
- **FR-022**: Every published message MUST carry the envelope `eventId` (new UUID), `occurredAt`,
  `aggregateId` = reservation id and `version` = 1, MUST use the reservation id as its message key,
  MUST be JSON, and MUST match `ecommerce-infra/docs/events.md` exactly. That file MUST be updated
  in the same change set to mark these inventory events and the Inventory consumption of
  `orders.order-placed` as implemented.
- **FR-023**: If publishing fails after the save, the system MUST log the failure with full context
  (topic, key, event id, order id, cause) and store the complete outcome message as an outbound
  dead-letter entry with status Pending, retry count 0, a next-retry time and the last error. The
  reservation MUST remain committed. Redelivering these entries is out of scope (US-03).

**Inbound failure handling**

- **FR-024**: A valid message whose processing fails MUST be retried with exponentially increasing
  delays. After the retries are exhausted it MUST be stored as an inbound dead-letter entry (raw
  content, topic, partition, offset, key, error, attempt count, time), the failure MUST be logged
  with full context, and its offset MUST be committed so the partition is not blocked.
- **FR-025**: Business rejections (insufficient stock, unknown product) and ignored duplicates are
  normal outcomes, not failures, and MUST NOT be retried or dead-lettered.

**Local data**

- **FR-026**: In the local development environment only, the system MUST provide these sample
  products: SKU-001 (10), SKU-002 (25), SKU-003 (100), SKU-004 (5), SKU-005 (0). The sample data
  MUST NOT be part of the production schema history, MUST NOT be created in tests or production,
  MUST NOT duplicate or reset existing products when applied again, and MUST be documented in the
  README.

**Out of scope**

- **FR-027**: This feature MUST NOT add any HTTP API, stock management or restocking operation,
  price handling, release of stock on cancellation (US-02) or redelivery of outbound dead-letter
  entries (US-03).

### Key Entities

- **Stock item**: a product Inventory can supply. Identified by `productId` (text, e.g.
  `SKU-001`); holds the available quantity (never negative). Created only by seed data or future
  stock-management stories.
- **Reservation**: the outcome of handling one order. Reservation id, order id (unique), status
  (Reserved or Rejected; Released is introduced by US-02), consolidated items (product id and
  requested quantity), rejection reason and failing items (requested, available) when Rejected,
  created and updated timestamps.
- **Processed event**: a record that an inbound message (`eventId`) was handled, with the topic,
  the time it was processed and a reference to the outcome message it produced (if any), used for
  idempotency and for republishing on redelivery.
- **Outbound dead-letter entry**: an outcome message that could not be published: event id, topic,
  key, full payload, status (Pending, later Resolved or Exhausted), retry count, next-retry time,
  last error, created and updated timestamps.
- **Inbound dead-letter entry**: a consumed message that could not be processed: raw content,
  topic, partition, offset, key, error, attempt count, time.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an order that can be fully supplied, the stock-reserved outcome is published
  within 2 seconds of the order-placed message becoming available to Inventory (local environment,
  normal load).
- **SC-002**: Across a test of 20 concurrent orders for a product with 5 units, and across
  repeated runs, 0 units are oversold, stock is never negative, and 100% of orders receive exactly
  one outcome.
- **SC-003**: 100% of rejected orders leave every product's stock exactly as it was before the
  order was handled.
- **SC-004**: Delivering any already processed message again changes stock in 0% of cases, and
  every redelivery yields at most one additional outcome message carrying the original event id.
- **SC-005**: Every published outcome passes validation against the contract in
  `ecommerce-infra/docs/events.md` (all required fields present, correct types, key equals
  reservation id).
- **SC-006**: A malformed or permanently failing message never stops the partition: the next valid
  message on the same partition is processed, and the bad message is found in the inbound
  dead-letter store, in 100% of tested cases.
- **SC-007**: When publishing is made to fail, 100% of the affected outcomes are found in the
  outbound dead-letter store with status Pending, and no saved reservation is lost.
- **SC-008**: A developer following the README can place an order against the sample products and
  see both a reservation (e.g. SKU-003) and a rejection (e.g. SKU-005) within 10 minutes of
  starting the local infrastructure.

## Assumptions

- `orders.order-placed` is already published by Orders as described in
  `ecommerce-infra/docs/events.md`; Orders consolidates items itself, and Inventory consolidates
  again defensively.
- Only `version` 1 of `orders.order-placed` exists; any other version is treated as invalid.
- `customerId`, `unitPrice` and `totalAmount` are present in the message but are not used by
  Inventory (prices are out of scope).
- The rejection reason uses three fixed values (FR-008), extending the examples given in
  `events.md` (`insufficient stock`, `unknown product`) with a combined value for mixed failures.
  `events.md` is updated to list the three values.
- `reservedAt` / `rejectedAt` and the envelope `occurredAt` are the time the reservation decision
  was saved, in UTC.
- Inbound retry policy: up to 3 retries after the first attempt, with exponentially increasing
  delays starting at about 1 second, before dead-lettering. Exact values are configurable.
- The first outbound next-retry time is set shortly after the failure (for example 30 seconds);
  the redelivery policy itself is defined in US-03.
- Product ids are free-form text compared exactly; there is no product catalogue lookup.
- Stock items exist only through seed data in this story; production stock loading is a future
  stock-management story.
- "Local development environment" is an explicitly selected run mode used with ecommerce-infra;
  automated tests create their own data.
- Orders does not yet consume the inventory outcomes; that is a separate Orders story, and until
  then the published messages have no consumer other than tests and tooling.
- US-02 (release) will need to handle cancellations arriving before placements (ADR 0003 open
  point); this story does not address it.
