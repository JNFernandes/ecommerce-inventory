# Feature Specification: Release Reserved Stock When an Order Is Cancelled (US-02)

**Feature Branch**: `feature/US-02-release-stock`

**Created**: 2026-11-13

**Status**: Draft

**Input**: User description: "US-02: Release reserved stock when an order is cancelled. Inventory
consumes `orders.order-cancelled` and releases the stock reserved for that order, then publishes
`inventory.stock-released`. Release only Reserved reservations, atomically; ignore with a log line
when there is no reservation, it is Rejected or already Released. Race rule: a cancellation that
arrives before the reservation leaves a cancellation marker, and a later OrderPlaced for that order
reserves nothing and publishes nothing. Same idempotency, write flow, outbound and inbound
dead-letter handling as US-01. Out of scope: retry worker (US-03), partial releases, HTTP API,
restocking."

## Context

Orders cancels an order when its payment fails or its stock is rejected (ADR 0003). Cancellation is
the saga's compensation step: Payments refunds a processed payment, and Inventory must give back
any stock it reserved for that order so the units can be sold again.

This feature builds on US-01 (reserve stock): the same reservation, idempotency, write-flow and
dead-letter rules apply. The message contracts are defined in `ecommerce-infra/docs/events.md`:

- **Consumed — order cancelled** (`orders.order-cancelled`): envelope (`eventId`, `occurredAt`,
  `aggregateId` = order id, `version`) plus `customerId` and an optional `reason`.
- **Produced — stock released** (`inventory.stock-released`): envelope (`aggregateId` =
  reservation id) plus `orderId`, `items` (`productId`, `quantity`) and `releasedAt`.

The cancellation and the placement of an order travel on different topics, so Inventory can receive
them in either order (ADR 0003, open point). This feature closes that gap.

## User Scenarios & Testing *(mandatory)*

The "users" are the other services in the saga (Orders, and indirectly the customers whose orders
need the released stock) and the developers and operators who run the service.

### User Story 1 - Give back the stock of a cancelled order (Priority: P1)

When an order that has stock reserved is cancelled, Inventory adds every reserved quantity back to
the available stock, marks the reservation as released, and announces that the stock was released.

**Why this priority**: Without it, every cancelled order keeps its units locked forever and the
products become unsellable. It is the core of the feature.

**Independent Test**: With an order reserved (for example 3 × SKU-001 from 10, leaving 7), publish
an order-cancelled message for it; verify SKU-001 is back to 10, the reservation is Released, and
one stock-released message for the order was published.

**Acceptance Scenarios**:

1. **Given** a Reserved reservation for an order with 3 × SKU-001 and 5 × SKU-002, and SKU-001 has
   7 and SKU-002 has 20 available, **When** the order is cancelled, **Then** SKU-001 has 10 and
   SKU-002 has 25 available, the reservation status is Released, and one stock-released message is
   published with the order id, both items with their reserved quantities, and the release time.
2. **Given** a reservation whose items were consolidated (2 + 3 × SKU-001 reserved as 5), **When**
   the order is cancelled, **Then** exactly 5 units of SKU-001 are given back and the message lists
   one SKU-001 item with quantity 5.
3. **Given** other orders reserved stock of the same product after this order, **When** this order
   is cancelled, **Then** only this order's quantities are given back; other reservations are not
   affected.
4. **Given** a successful release, **When** the stock-released message is inspected, **Then** its
   key and `aggregateId` are the reservation id, its `orderId` is the cancelled order, and it carries
   a new `eventId`, `occurredAt` and `version` 1.

---

### User Story 2 - Ignore cancellations with nothing to give back (Priority: P1)

A cancellation for an order whose reservation was rejected, or was already released, gives nothing
back. Inventory records that it saw the cancellation, writes a log line, changes no stock and
publishes nothing.

**Why this priority**: Most cancellations come from failures, so many cancelled orders were
rejected by Inventory in the first place. Giving stock back for them, or twice for the same order,
would create stock out of thin air (overselling later). It is as critical as Story 1.

**Independent Test**: Cancel an order whose reservation is Rejected, and cancel an already Released
order again with a new event; verify no stock changes and nothing is published in both cases.

**Acceptance Scenarios**:

1. **Given** an order whose reservation is Rejected, **When** the order is cancelled, **Then** no
   stock changes, the reservation stays Rejected, nothing is published, and a log line records the
   order id, the event id and that the reservation was rejected.
2. **Given** an order whose reservation is already Released, **When** another order-cancelled
   message with a different `eventId` arrives for it, **Then** no stock changes, nothing is
   published, and a log line records that the reservation was already released.

---

### User Story 3 - A cancellation that arrives first prevents the reservation (Priority: P1)

If the cancellation of an order arrives before Inventory has handled the placement of that order,
Inventory remembers that the order is cancelled. When the placement arrives later, Inventory
reserves nothing for it and publishes nothing.

**Why this priority**: Without it, stock is reserved forever for an order that no longer exists:
the release already "happened" (as a no-op) before the reservation was made, and nothing will ever
give the stock back. It is a correctness hole in the saga, not an edge nicety.

**Independent Test**: Publish an order-cancelled message for an order Inventory has never seen,
then publish its order-placed message; verify no stock changes, no reservation exists, and nothing
is published to any inventory topic for that order.

**Acceptance Scenarios**:

1. **Given** Inventory has never seen order X, **When** an order-cancelled message for X arrives,
   **Then** Inventory records that X is cancelled, changes no stock, publishes nothing, and logs
   that the cancellation arrived before the placement.
2. **Given** order X was recorded as cancelled, **When** an order-placed message for X arrives
   (with items that could be fully supplied), **Then** no stock is reserved, no reservation is
   created, nothing is published, and a log line records that the order was already cancelled.
3. **Given** the placement of order Y arrives first and is reserved, **When** its cancellation
   arrives afterwards, **Then** the stock is released as in Story 1 (normal order of arrival).
4. **Given** the placement and the cancellation of the same order are processed at the same moment,
   **When** both have been handled, **Then** the order ends with no stock held for it: either it was
   never reserved, or it was reserved and released. It never ends Reserved.

---

### User Story 4 - Process each cancellation once despite redeliveries (Priority: P2)

A cancellation message can be delivered more than once. Inventory handles it once: a redelivered
message never gives stock back a second time.

**Why this priority**: Required for correctness under at-least-once delivery, but only exercised
when messages are redelivered.

**Independent Test**: Publish the same order-cancelled message twice (same `eventId`) for a
reserved order; verify the stock is given back once and the stock-released outcome is not
duplicated with a different event id.

**Acceptance Scenarios**:

1. **Given** a cancellation was processed and its stock released, **When** the same message (same
   `eventId`) is delivered again, **Then** stock is unchanged by the redelivery and at most the
   original stock-released message is published once more, with its original `eventId`.
2. **Given** a cancellation was processed and ignored (rejected, already released, or recorded as
   arriving first), **When** the same message is delivered again, **Then** nothing changes and
   nothing is published.

---

### User Story 5 - Keep the flow moving when messaging fails (Priority: P2)

Failures follow exactly the US-01 rules: an outcome that cannot be published after the release was
saved is kept for later redelivery, and a cancellation message that cannot be processed is retried
and then set aside so the following messages keep flowing.

**Why this priority**: Protects the saga from losing releases and from stalling, but only applies
when something goes wrong.

**Independent Test**: (a) Make publishing fail after a release and verify the stock-released message
is stored as a pending outbound dead-letter entry while the release stays saved. (b) Send a
malformed cancellation followed by a valid one and verify the malformed one is set aside and the
valid one is processed.

**Acceptance Scenarios**:

1. **Given** a release was saved, **When** publishing its stock-released message fails, **Then** the
   release stays committed and the full message is stored as a pending outbound dead-letter entry,
   in the same store and format as US-01.
2. **Given** an order-cancelled message that is not valid JSON or lacks required fields, **When** it
   is consumed, **Then** it is not retried, nothing changes, it is stored in the same inbound
   dead-letter store as US-01, its offset is committed and the next message is processed.
3. **Given** a valid cancellation whose processing keeps failing, **When** the retries are
   exhausted, **Then** it is stored as an inbound dead-letter entry and the following messages are
   processed.

---

### Edge Cases

- **Cancellation without `reason`**: `reason` is optional; its presence or absence does not change
  the behaviour.
- **Cancellation without `customerId`** or with unknown extra fields: not used by Inventory; the
  message is still processed (additive changes keep the version).
- **Unsupported version**: a message with `version` other than 1 is invalid and goes to the inbound
  dead-letter store.
- **Two cancellations for the same order with different event ids**: the first one releases (or
  records the early cancellation); the second finds the order Released (or already marked) and is
  ignored with a log line.
- **Cancellation arrives first, then a duplicate cancellation, then the placement**: the order is
  marked once; the placement is ignored; nothing is ever reserved.
- **Placement redelivered after it was ignored because of a marker**: still ignored; nothing
  reserved, nothing published.
- **Released stock goes back to products that have since been reserved by other orders**: the
  released quantities are added to whatever is currently available; other orders' reservations are
  untouched.
- **A product of the reservation no longer has a stock record**: cannot happen today (stock records
  are never deleted); if it did, it is treated as a processing failure (retried, then
  dead-lettered), never as a partial release.
- **Release and new reservations of the same products at the same moment**: no update is lost and
  stock never goes negative; products are claimed in the same deterministic order as reservations,
  so they cannot block each other indefinitely.
- **Crash after the release is saved but before publishing**: on redelivery the stored
  stock-released message is republished with its original `eventId`, so the outcome is not lost.

## Requirements *(mandatory)*

### Functional Requirements

**Consuming**

- **FR-001**: The system MUST consume `orders.order-cancelled` and use the envelope `aggregateId` as
  the order id.
- **FR-002**: The system MUST validate each cancellation before processing: present `eventId` (UUID),
  `occurredAt` (timestamp), `aggregateId` (UUID) and `version` equal to 1. `customerId` and `reason`
  are optional and not used; unknown fields are ignored.
- **FR-003**: A cancellation that cannot be parsed or fails validation MUST NOT be retried or
  partially processed; it MUST be stored in the existing inbound dead-letter store and its offset
  committed.

**Release decision**

- **FR-004**: The system MUST find the order's reservation by order id.
- **FR-005**: If the reservation is Reserved, the system MUST add every reserved (consolidated)
  quantity back to the available stock of its product and mark the reservation Released, all
  together or not at all.
- **FR-006**: If the reservation is Rejected, the system MUST change nothing, publish nothing and log
  the order id, event id and the reason it was ignored.
- **FR-007**: If the reservation is already Released, the system MUST change nothing, publish
  nothing and log it. Stock MUST NEVER be given back twice for the same reservation.
- **FR-008**: A reservation MUST only move Reserved → Released; Rejected and Released are final.
- **FR-009**: Releasing MUST NOT affect other reservations and MUST NOT make any available quantity
  negative or lose concurrent updates; stock records MUST be claimed in the same deterministic order
  (ascending product id) as reservations.

**Cancellation that arrives first (race rule)**

- **FR-010**: If no reservation exists for the order, the system MUST record that the order is
  cancelled (a cancellation marker with the order id, the event id and the time), change no stock,
  publish nothing and log that the cancellation arrived before the placement.
- **FR-011**: When an order-placed message arrives for an order with a cancellation marker, the
  reserve-stock handling (US-01) MUST reserve nothing, create no reservation, publish nothing and log
  that the order was already cancelled. The message MUST still be recorded as processed.
- **FR-012**: Whatever the timing, including a placement and a cancellation of the same order
  processed at the same moment, an order that has been cancelled MUST NOT end with stock held for
  it.
- **FR-013**: At most one cancellation marker MUST exist per order.

**Idempotency and write flow**

- **FR-014**: The system MUST record each processed cancellation `eventId` in the same processed-events
  store as US-01. A message whose `eventId` was already processed MUST NOT change stock, reservations
  or markers.
- **FR-015**: For a redelivered cancellation that had released stock, the system MUST republish the
  original stock-released message with its original `eventId` and payload; for a redelivered
  cancellation that was ignored or recorded as arriving first, it MUST publish nothing.
- **FR-016**: The stock changes, the reservation status change, the stock-released message to be
  published, any cancellation marker and the processed-event record MUST be saved together
  atomically.
- **FR-017**: The stock-released message MUST be published only after that save has succeeded, and
  the consumed offset MUST be committed only after the save (or after the message was dead-lettered).

**Publishing**

- **FR-018**: On release the system MUST publish one message to `inventory.stock-released` with
  `orderId`, `items` (`productId`, `quantity`: the consolidated reserved quantities, sorted by
  product id) and `releasedAt`, plus the envelope `eventId` (new UUID), `occurredAt` (= `releasedAt`),
  `aggregateId` = reservation id and `version` = 1, keyed by the reservation id, as JSON matching
  `ecommerce-infra/docs/events.md`. That file MUST be updated in the same change set to mark the
  event and the Inventory consumption of `orders.order-cancelled` as implemented.
- **FR-019**: If publishing fails after the save, the system MUST log it with full context and store
  the complete message in the existing outbound dead-letter store (Pending, retry count 0, next-retry
  time, last error), exactly as US-01. The release MUST remain committed.

**Inbound failure handling**

- **FR-020**: Processing failures of a valid cancellation MUST follow the US-01 policy: retries with
  exponential backoff, then the inbound dead-letter store and an offset commit. No second retry or
  dead-letter mechanism may be introduced.
- **FR-021**: Ignored cancellations (rejected, already released, recorded as arriving first) and
  placements ignored because of a marker are normal outcomes, not failures, and MUST NOT be retried
  or dead-lettered.

**Out of scope**

- **FR-022**: This feature MUST NOT add partial releases, restocking or stock management, any HTTP
  API, or the redelivery of outbound dead-letter entries (US-03).

### Key Entities

- **Reservation** (from US-01): gains the status **Released** and the time it was released. Allowed
  transitions: Reserved → Released only.
- **Cancellation marker**: records that an order was cancelled before Inventory handled its
  placement: order id (unique), the cancellation event id, and when it was recorded. Used only to
  stop a later reservation.
- **Stock item** (from US-01): its available quantity increases by the released quantities.
- **Processed event** (from US-01): also records cancellations, with what they produced (released,
  ignored, or recorded as arriving first) and, when released, a link to the stored outcome.
- **Outcome message** (from US-01): the stored stock-released message of a release, republished on
  redelivery. A reservation can now have two outcomes over its life: its reserve outcome and its
  release outcome.
- **Outbound and inbound dead-letter entries** (from US-01): reused unchanged.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a reserved order, the stock-released outcome is published within 2 seconds of the
  order-cancelled message becoming available to Inventory (local environment, normal load).
- **SC-002**: After any sequence of cancellations, redeliveries and duplicate cancellations, the
  stock given back for each reservation equals its reserved quantities exactly once: 0 cases of stock
  returned twice and 0 cases of stock returned for a rejected order.
- **SC-003**: In 100% of tested cases where the cancellation arrives before the placement (including
  both being processed at the same moment), the order ends with no stock held and nothing published
  for the placement.
- **SC-004**: When a placement and a release touch the same products concurrently, available stock
  is never negative and the final quantities equal the starting quantities minus active reservations,
  in 100% of repeated runs.
- **SC-005**: Every published stock-released message passes validation against the contract in
  `ecommerce-infra/docs/events.md` (all fields present, correct types, key equals reservation id).
- **SC-006**: A malformed or permanently failing cancellation never stops the partition and is found
  in the inbound dead-letter store; a failed publish is found in the outbound dead-letter store as
  Pending, in 100% of tested cases.

## Assumptions

- `orders.order-cancelled` is published by Orders as described in `events.md`, keyed by the order id,
  with `version` 1. Only version 1 is accepted.
- The reservation for an order is found by the order id carried in the cancellation's `aggregateId`.
- Releases give back exactly the consolidated quantities stored on the reservation (US-01), never a
  subset (partial releases are out of scope).
- `releasedAt` and `occurredAt` of the stock-released message are the time the release was saved,
  in UTC.
- Cancellation markers are kept indefinitely; cleaning them up is a future concern (they are small,
  one per early cancellation).
- A placement ignored because of a marker creates no reservation, so the order has no reservation
  and a later cancellation for it finds the marker again and is ignored.
- Orders does not need to be told that a placement was ignored because of a marker: the order is
  already cancelled on the Orders side.
- Orders does not consume `inventory.stock-released`; it is informational (Notifications may consume
  it later, per `events.md`).
- Retry, backoff and dead-letter settings are the US-01 values and configuration.
