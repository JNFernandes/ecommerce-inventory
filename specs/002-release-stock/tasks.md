---

description: "Task list for US-02 Release Reserved Stock When an Order Is Cancelled"
---

# Tasks: Release Reserved Stock When an Order Is Cancelled (US-02)

**Input**: Design documents from `specs/002-release-stock/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/kafka-events.md](contracts/kafka-events.md),
[quickstart.md](quickstart.md)

**Tests**: REQUIRED by constitution Principles VI and VII (unit tests for every domain and
application method with a happy and an error/edge case; integration tests on real PostgreSQL;
component tests on real PostgreSQL + Kafka). Within each story write the tests first.

**Organization**: by the spec's user stories (Story 1–5 → `[US1]`–`[US5]`). These labels are the
stories inside this spec, not backlog ids (`US-02` is this whole feature).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- Paths: `M/` = `src/main/java/com/ecommerce/inventory/`, `T/` = `src/test/java/com/ecommerce/inventory/`,
  `R/` = `src/main/resources/`

## Rules that apply to every task

- **Quality gate after every task** with JDK 21 on `JAVA_HOME`: `.\mvnw.cmd -B -q compile`,
  `.\mvnw.cmd -B -q checkstyle:check`, `.\mvnw.cmd -B -q spotless:check` (fix with
  `.\mvnw.cmd -B -q spotless:apply`), `.\mvnw.cmd -B verify`. Never commit a failing gate.
- Javadoc on every public type, method and record component; `package-info.java` for new packages.
- `domain` imports nothing from Spring, `jakarta.persistence` or Kafka. Public methods never return
  `null`. No raw types, no `Throwable` catches.
- Component tests: every spy, mock and property override stays in `T/support/AbstractComponentIT`
  (one shared Spring context; a second context would steal Kafka messages).
- Reuse US-01 mechanisms; never add a second retry, dead-letter or idempotency path (FR-020).
- Conventional Commits with scope `US-02`.

---

## Phase 1: Setup

- [X] T001 Add topics to configuration: in `R/application.yml` add `inventory.kafka.topics.order-cancelled: ${KAFKA_TOPIC_ORDER_CANCELLED:orders.order-cancelled}` and `stock-released: ${KAFKA_TOPIC_STOCK_RELEASED:inventory.stock-released}`; add `@NotBlank String orderCancelled` and `@NotBlank String stockReleased` (with `@param` Javadoc) to `InventoryProperties.Topics` in `M/infrastructure/config/InventoryProperties.java`; update the test helper `KafkaOutboundMessagePublisherTest.properties(...)` in `T/infrastructure/kafka/KafkaOutboundMessagePublisherTest.java` to pass the two new topic names
- [X] T002 Test infrastructure: add `orders.order-cancelled` and `inventory.stock-released` to `KafkaTestContainer.TOPICS` in `T/support/KafkaTestContainer.java`; move the "wait until the listener has partitions" `@BeforeEach` from `T/component/ReserveStockComponentIT.java` into `T/support/AbstractComponentIT.java` and make it wait for every container in `KafkaListenerEndpointRegistry` (so it also covers the US-02 listener once it exists); move the shared helpers `orderPlaced(...)`, `line(...)` and `count(...)` into the base class as `protected` methods and add `orderCancelled(UUID eventId, UUID orderId)` building a valid cancellation JSON (with `customerId` and `reason`)

**Checkpoint**: gate green; no behaviour change.

---

## Phase 2: Foundational (US-01 refactors and schema)

**Purpose**: changes every story depends on. **⚠️ No story work before this phase is done.**

### Schema

- [X] T003 Create `R/db/migration/V3__release_stock.sql` per [data-model.md §3](data-model.md): `reservations` status check adds `RELEASED`, new nullable `released_at timestamptz`, check `(status = 'RELEASED') = (released_at is not null)`; drop `uq_outcome_messages_reservation` and add index `ix_outcome_messages_reservation (reservation_id)`; `processed_events.reservation_id` drop not null; add `processed_events.outcome_event_id uuid references outcome_messages(event_id)`; backfill it with `update processed_events p set outcome_event_id = o.event_id from outcome_messages o where o.reservation_id = p.reservation_id and p.outcome = 'RESERVATION_DECIDED'`; replace the outcome check with the six values (`RESERVATION_DECIDED`, `DUPLICATE_ORDER_IGNORED`, `STOCK_RELEASED`, `CANCELLATION_IGNORED`, `CANCELLATION_RECORDED`, `PLACEMENT_CANCELLED`); add check `(outcome_event_id is not null) = (outcome in ('RESERVATION_DECIDED', 'STOCK_RELEASED'))`; create `cancellation_markers (order_id uuid primary key, event_id uuid not null, created_at timestamptz not null)`
- [X] T004 [P] Write `T/infrastructure/persistence/ReleaseSchemaMigrationIT.java` (extends `AbstractPostgresIT`): should refuse `RELEASED` without `released_at` and `RESERVED` with it; should allow two `outcome_messages` for one reservation; should refuse a `STOCK_RELEASED` processed event without `outcome_event_id`; should refuse a second marker for the same order; should backfill `outcome_event_id` — run Flyway programmatically on a fresh schema (`Flyway.configure().dataSource(...).schemas("v3_backfill").locations("classpath:db/migration").target("2").load().migrate()`), insert a reservation, an outcome message and a `RESERVATION_DECIDED` processed event, migrate to latest, and assert the processed event now references the outcome; drop the schema afterwards

### Idempotency records (US-01 change)

- [X] T005 Extend `M/application/port/ProcessedOutcome.java` with `STOCK_RELEASED`, `CANCELLATION_IGNORED`, `CANCELLATION_RECORDED`, `PLACEMENT_CANCELLED` (Javadoc each); change `M/application/port/ProcessedEvent.java` to `(UUID eventId, String topic, ProcessedOutcome outcome, Optional<ReservationId> reservationId, Optional<UUID> outcomeEventId, Instant processedAt)` with non-null checks on the Optionals; update `ProcessedEventEntity` (nullable `reservation_id`, new `outcome_event_id`) and `ProcessedEventRepositoryAdapter` in `M/infrastructure/persistence/`; update `ReserveStockService` to record `outcomeEventId` = the outcome message event id for `RESERVATION_DECIDED` and `Optional.empty()` for `DUPLICATE_ORDER_IGNORED`; update `T/application/ReserveStockServiceTest.java` and `T/infrastructure/persistence/OutcomeAndProcessedEventAdaptersIT.java` to the new shape (round trip with and without reservation id / outcome event id)
- [X] T006 Replace `findByReservationId` with `Optional<OutboundMessage> findByEventId(UUID eventId)` in `M/application/port/OutcomeMessageRepository.java`, `M/infrastructure/persistence/OutcomeMessageRepositoryAdapter.java` and `OutcomeMessageJpaRepository` (use `findById`); in `ReserveStockService` change the redelivery rule: republish **only** when the processed outcome is `RESERVATION_DECIDED`, looking the message up with `processed.outcomeEventId()` → `findByEventId` and failing loudly (`IllegalStateException` naming the event id) when it is absent; **any other processed outcome** (`DUPLICATE_ORDER_IGNORED`, and from T027 `PLACEMENT_CANCELLED`) publishes nothing and returns the matching outcome, never an exception (spec edge case "placement redelivered after it was ignored", FR-021); update the affected tests (`ReserveStockServiceTest` redelivery, missing-outcome and "other outcome publishes nothing" cases, `OutcomeAndProcessedEventAdaptersIT`)

### Shared publishing (US-01 change)

- [X] T007 Extract `M/application/OutcomePublisher.java` (`@Component`): `void publish(OutboundMessage message, OrderId orderId)` with exactly the current `publishOrDeadLetter` behaviour (publish; on `PublishFailedException` log with topic, key, event id, order id and store via `OutboundDeadLetterRepository.recordFailure` at `now + DeadLetterPolicy.firstRetryDelay()`; if that throws, log the full payload); `ReserveStockService` depends on `OutcomePublisher` instead of publisher + dead-letter repository + policy; move the four publish-failure tests from `ReserveStockServiceTest` to new `T/application/OutcomePublisherTest.java` and keep one test in `ReserveStockServiceTest` verifying the service hands the message to `OutcomePublisher` after the commit

### Per-order lock (research R1)

- [X] T008 Create the port `M/application/port/OrderLock.java` (`void lock(OrderId orderId)`, Javadoc: serialises all work on one order until the current transaction ends) and `M/infrastructure/persistence/PostgresOrderLock.java` (`@Repository`): runs `select set_config('lock_timeout', ?, true)` with `InventoryProperties.stock().lockTimeout()` then `select pg_advisory_xact_lock(?)` with key `orderId.getMostSignificantBits() ^ orderId.getLeastSignificantBits()` via `JdbcTemplate`; must be called inside a transaction (document it)
- [X] T009 [P] Write `T/infrastructure/persistence/PostgresOrderLockIT.java`: while transaction A holds the lock for order X, transaction B locking X waits until A commits (assert B finishes after A, using latches and Awaitility), B locking order Y does not wait, and with `inventory.stock.lock-timeout=300ms` B fails with a `PessimisticLockingFailureException`/`CannotAcquireLockException` if A holds X longer
- [X] T010 In `M/application/ReserveStockService.java` call `orderLock.lock(command.orderId())` as the first statement inside the transaction; add a `ReserveStockServiceTest` case asserting (with the journaling transaction stub) that the lock is taken after `begin` and before `processedEvents.find`; verify `ConcurrentReservationIT` still passes

### Shared parsing (US-01 change)

- [X] T011 Extract the strict reader settings and the validate-or-throw logic of `M/messaging/OrderPlacedMessageParser.java` into `M/messaging/StrictJsonParser.java` (generic `<T> T parse(String raw, Class<T> type, String contractName)` throwing `InvalidMessageException`); `OrderPlacedMessageParser` delegates to it; `OrderPlacedMessageParserTest` must pass unchanged

### Reservation persistence and markers

- [X] T012 Domain and persistence support for released reservations: add `RELEASED` to `M/domain/model/ReservationStatus.java`; add `Optional<Instant> releasedAt()` and `static Reservation restoreReleased(ReservationId, OrderId, RequestedItems, Instant createdAt, Instant releasedAt)` to `M/domain/model/Reservation.java` with the invariant "releasedAt present ⇔ RELEASED" in the constructor; add `void update(Reservation reservation)` to `M/domain/repository/ReservationRepository.java`; map `released_at` in `ReservationEntity`/`ReservationMapper` and implement `update` in `ReservationRepositoryAdapter` (load by id, set status, `released_at`, `updated_at`); extend `T/infrastructure/persistence/ReservationRepositoryAdapterIT.java` with a released round trip and an update from RESERVED to RELEASED; extend `ReservationReserveTest` with the new invariant
- [X] T013 [P] Cancellation markers: `M/domain/model/CancellationMarker.java` (record `OrderId orderId, UUID eventId, Instant createdAt`, non-null), `M/domain/repository/CancellationMarkerRepository.java` (`boolean exists(OrderId)`, `void save(CancellationMarker)`), `CancellationMarkerEntity`, `CancellationMarkerJpaRepository` and `CancellationMarkerRepositoryAdapter` in `M/infrastructure/persistence/`; write `T/infrastructure/persistence/CancellationMarkerRepositoryAdapterIT.java` (save then exists; unknown order does not exist; a second save for the same order fails with `DataIntegrityViolationException`)

**Checkpoint**: gate green; all US-01 tests still pass with the refactors.

---

## Phase 3: User Story 1 — Give back the stock of a cancelled order (P1) 🎯 MVP

**Goal**: a cancellation of a `RESERVED` order returns every reserved quantity, marks it `RELEASED`
and publishes `inventory.stock-released`.

**Independent Test**: reserve 3 × SKU-001 (10 → 7), cancel the order; SKU-001 back to 10, reservation
`RELEASED`, one `inventory.stock-released` message keyed by the reservation id.

### Tests (write first)

- [ ] T014 [P] [US1] Write `T/domain/model/ReservationReleaseTest.java` (release path): should add every reserved quantity back and return `RELEASED`; should set status `RELEASED`, `releasedAt` and `updatedAt` to now; should release the consolidated quantity (2 + 3 reserved as 5 gives back 5); should raise exactly one `StockReleased` with reservation id, order id, items and `releasedAt`; should throw `StockItemMissingException` and change nothing when a reserved product is missing from the locked stock; add `StockItem.release` cases to `T/domain/model/StockItemTest.java` (adds the quantity; overflow beyond `Integer.MAX_VALUE` throws `InvalidQuantityException`)
- [ ] T015 [P] [US1] Add `StockReleased` cases to `T/infrastructure/kafka/JsonOutboundMessageFactoryTest.java`: topic `inventory.stock-released`, key and `aggregateId` = reservation id, exact fields `eventId, occurredAt, aggregateId, version, orderId, items, releasedAt`, item fields `productId, quantity`, `occurredAt == releasedAt`

### Implementation

- [ ] T016 [US1] Domain: `M/domain/model/ReleaseOutcome.java` (`RELEASED`, `NOTHING_RESERVED`, `ALREADY_RELEASED`), `M/domain/exception/StockItemMissingException.java`, `StockItem.release(Quantity)` with `Math.addExact` and an `Integer.MAX_VALUE` check, `M/domain/event/StockReleased.java` (record; add to the `permits` of `InventoryDomainEvent`), and `ReleaseOutcome Reservation.release(Map<ProductId, StockItem> lockedStock, Instant now)` per [data-model.md §1](data-model.md) (status becomes mutable; check every product is present before changing any stock; `REJECTED` → `NOTHING_RESERVED`, `RELEASED` → `ALREADY_RELEASED`, no event for those)
- [ ] T017 [P] [US1] `M/infrastructure/kafka/message/StockReleasedMessage.java` and `ReleasedItem.java` (records, Javadoc on every component per `events.md`); add the `StockReleased` case to the exhaustive switch in `M/infrastructure/kafka/JsonOutboundMessageFactory.java` (topic `topics.stockReleased()`)
- [ ] T018 [P] [US1] `M/messaging/dto/OrderCancelledMessage.java` (record `@NotNull UUID eventId`, `@NotNull Instant occurredAt`, `@NotNull UUID aggregateId`, `@NotNull Integer version`, `UUID customerId`, `String reason`; `@AssertTrue isSupportedVersion()`), `M/messaging/OrderCancelledMessageParser.java` (delegates to `StrictJsonParser`), and `T/messaging/OrderCancelledMessageParserTest.java` (parses the contract example; accepts a missing `reason` and `customerId`; ignores unknown fields; rejects non-JSON, missing `eventId`, missing `aggregateId`, `version` 2)
- [ ] T019 [US1] `M/application/ReleaseStockCommand.java` (record `UUID eventId, String topic, OrderId orderId`), `M/application/ReleaseStockOutcome.java` (sealed: `Released(ReservationId)`, `Ignored(Optional<ReservationId>, String reason)`, `CancellationRecorded()`, `Redelivered(ReservationId)`), and `M/application/ReleaseStockService.java` (`@Service`, no `@Transactional`): inside `TransactionOperations` → `orderLock.lock` → `processedEvents.find` (redelivery handled in T030; until then treat a known event id as `Ignored`) → `reservations.findByOrderId` (no reservation handled in T026; until then `Ignored` with `CANCELLATION_IGNORED`) → `stockItems.lockAllOrderedByProductId(reservation.items().productIds())` → `reservation.release(...)` → for `RELEASED`: `stockItems.saveAll`, `reservations.update`, `messageFactory.create(event)`, `outcomeMessages.save`, `processedEvents.save(STOCK_RELEASED, reservation id, outcome event id)`; after commit `outcomePublisher.publish(message, orderId)`; log `Order {} cancelled: reservation {} RELEASED, inbound event {}, published event {}`
- [ ] T020 [US1] Write `T/application/ReleaseStockServiceTest.java` (Mockito, journaling transaction stub as in `ReserveStockServiceTest`): released path journal is `begin, lock, …, saveStock, updateReservation, saveOutcome, saveProcessed, commit, publish`; stock locked for exactly the reservation's product ids in ascending order; no publish when the transaction throws; `StockItemMissingException` propagates and nothing is published
- [ ] T021 [US1] `M/messaging/OrderCancelledListener.java` (`@KafkaListener(id = "order-cancelled-listener", idIsGroup = false, topics = "${inventory.kafka.topics.order-cancelled}")`, parse → `ReleaseStockCommand` → `service.release` → `ack.acknowledge()`); add `@MockitoSpyBean ReleaseStockService` with an accessor to `T/support/AbstractComponentIT.java`; write `T/messaging/OrderCancelledListenerTest.java` (maps and acknowledges after the service; no ack on failure; invalid message rethrows `InvalidMessageException` without calling the service)
- [ ] T022 [US1] Write `T/component/ReleaseStockComponentIT.java` (extends `AbstractComponentIT`): reserve 3 × SKU-001 + 5 × SKU-002 then cancel → SKU-001 and SKU-002 back to their start values, reservation `RELEASED` with `released_at`, one `inventory.stock-released` message within 2 s of the cancel (SC-001) keyed by the reservation id with the exact fields and items; consolidated lines (2 + 3) released as one item of 5; a second reservation of the same product by another order is not affected by the release

**Checkpoint**: Story 1 works end to end; gate green. MVP.

---

## Phase 4: User Story 2 — Ignore cancellations with nothing to give back (P1)

**Goal**: cancelling a `REJECTED` or already `RELEASED` order changes nothing and publishes nothing.

**Independent Test**: cancel a rejected order, and cancel a released order with a new event id; no
stock change and nothing published in both cases.

- [ ] T023 [P] [US2] Extend `T/domain/model/ReservationReleaseTest.java`: `REJECTED` → `NOTHING_RESERVED`, stock and status unchanged, no event; `RELEASED` → `ALREADY_RELEASED`, stock unchanged, no event, `releasedAt` unchanged
- [ ] T024 [US2] In `M/application/ReleaseStockService.java` handle `NOTHING_RESERVED` and `ALREADY_RELEASED`: save `processedEvents(CANCELLATION_IGNORED, reservation id, no outcome)`, no stock save, no publish, log `Cancellation of order {} ignored: {} (event {}, reservation {})` with "nothing was reserved" or "already released"; return `Ignored(reservationId, reason)`; add both cases to `T/application/ReleaseStockServiceTest.java` (verify `saveAll`, `update`, `outcomeMessages.save` and the publisher are never called)
- [ ] T025 [US2] Add to `T/component/ReleaseStockComponentIT.java`: cancel a rejected order (place 1 × SKU-005 with 0 stock, then cancel) → nothing on `inventory.stock-released`, stock unchanged, `CANCELLATION_IGNORED`; cancel a released order again with a new event id → nothing new published, stock restored only once; in both, `consumed_dead_letters` empty and the release service invoked exactly once per message (FR-021)

**Checkpoint**: Stories 1 and 2 work.

---

## Phase 5: User Story 3 — A cancellation that arrives first prevents the reservation (P1)

**Goal**: early cancellations leave a marker; later placements of that order reserve nothing; no
timing can leave a cancelled order `RESERVED`.

**Independent Test**: cancel an unknown order X, then place X; no stock change, no reservation,
nothing published.

- [ ] T026 [US3] In `M/application/ReleaseStockService.java`, when no reservation exists: if `cancellationMarkers.exists(orderId)` → `CANCELLATION_IGNORED` (duplicate early cancel); otherwise save `CancellationMarker(orderId, eventId, now)` and `processedEvents(CANCELLATION_RECORDED, no reservation, no outcome)`, log `Cancellation of order {} arrived before its placement: recorded (event {})`, return `CancellationRecorded`; add unit tests for both branches to `T/application/ReleaseStockServiceTest.java`
- [ ] T027 [US3] In `M/application/ReserveStockService.java`, right after the processed-event check (inside the order lock): if `cancellationMarkers.exists(orderId)` → save `processedEvents(PLACEMENT_CANCELLED, no reservation, no outcome)`, log `OrderPlaced for order {} ignored: order already cancelled (event {})`, no stock lock, no reservation, no publish; add `PlacementCancelled()` to `M/application/ReserveStockOutcome.java`; add unit tests to `T/application/ReserveStockServiceTest.java` (marker → nothing locked, saved or published; redelivery of that placement event, i.e. processed outcome `PLACEMENT_CANCELLED` → returns `PlacementCancelled`, publishes nothing and throws nothing, relying on the T006 rule)
- [ ] T028 [US3] Write `T/application/PlaceCancelRaceIT.java` (extends `AbstractPostgresIT`, real `ReserveStockService` and `ReleaseStockService`, `@MockitoBean OutboundMessagePublisher`): for 50 rounds start a placement (1 × SKU-001) and a cancellation of the same new order at the same moment (latch, two threads) and also 10 rounds each with a forced order (cancel first, then place; place first, then cancel); after each round assert the order is never `RESERVED` (either no reservation and a marker exist, or the reservation is `RELEASED`), and at the end SKU-001 equals its starting quantity and was never negative (SC-003). Add a second test for **releases racing other orders' reservations of the same product** (SC-004, FR-009): seed SKU-002 with 100, reserve 20 orders of 2 units, then in parallel cancel 10 of them while 20 new orders of 2 units reserve SKU-002 (all tasks released together by one latch); assert no deadlock or timeout, `available_quantity` never negative, and final SKU-002 = 100 − 2 × (number of reservations still `RESERVED`)
- [ ] T029 [US3] Add to `T/component/ReleaseStockComponentIT.java`: cancel then place (marker row, no reservation, nothing on `inventory.stock-reserved`, `stock-rejected` or `stock-released` for the order, stock unchanged, `PLACEMENT_CANCELLED`, `consumed_dead_letters` empty and `ReserveStockService.reserve` invoked exactly once for the placement, i.e. not retried, FR-021); place then cancel (normal release); duplicate early cancellation (second cancel with a new event id before the placement → one marker, `CANCELLATION_IGNORED`)

**Checkpoint**: Stories 1–3 work; the saga gap of ADR 0003 is closed.

---

## Phase 6: User Story 4 — Process each cancellation once despite redeliveries (P2)

**Goal**: a redelivered cancellation never gives stock back twice; a releasing one republishes the
stored message with its original event id.

- [ ] T030 [US4] In `M/application/ReleaseStockService.java` replace the temporary known-event handling of T019: if the event was processed with `STOCK_RELEASED` → republish `outcomeMessages.findByEventId(outcomeEventId)` (fail loudly if absent) after the commit and return `Redelivered`; any other outcome → nothing, return `Ignored`; add unit tests to `T/application/ReleaseStockServiceTest.java` (no lock of stock, no saves, correct republish)
- [ ] T031 [US4] Add to `T/component/ReleaseStockComponentIT.java`: the same cancellation (same event id) twice → stock restored once, two identical `stock-released` messages (same `eventId`, same key; compare JSON trees), one `STOCK_RELEASED` processed event; the same early cancellation twice → still one marker, nothing published

**Checkpoint**: Stories 1–4 work.

---

## Phase 7: User Story 5 — Keep the flow moving when messaging fails (P2)

**Goal**: prove the US-01 failure handling covers cancellations without new mechanisms.

- [ ] T032 [US5] Add to `T/component/ReleaseStockComponentIT.java`: invalid cancellation (`not json`) followed by a valid one with the same key → a `consumed_dead_letters` row for `orders.order-cancelled` with attempts 1, and the valid one processed; publish failure on a release (stub the publisher spy to throw `PublishFailedException` for that order's message) → release committed (stock restored, `RELEASED`), one `PENDING` row in `outbound_dead_letters` with topic `inventory.stock-released` and the full payload; persistent processing failure (stub the release service spy to throw for that order) → dead-lettered after the configured retries and the next cancellation processed. Fix production code only if a test reveals a gap; do not add new retry or dead-letter code (FR-020)

**Checkpoint**: all stories work.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T033 [P] Write `docs/adr/0005-per-order-serialisation.md` (Context / Decision / Consequences: per-order advisory lock in both handlers, cancellation markers, lock order before stock locks, alternatives rejected per research R1/R2); in `docs/adr/0003-inventory-in-order-saga.md` replace the "Open point" paragraph's conclusion with "Resolved by ADR 0005" and add `- **Amended**: <date> — open point resolved by ADR 0005; the decision is unchanged.` under its Status/Date lines (same convention as ADR 0001); add 0005 to `docs/adr/README.md`
- [ ] T034 [P] Update `README.md`: status (US-02 implemented), events table (order-cancelled consumed, stock-released produced), "How a release works" section, the cancel-before-place rule, new tables/columns (`cancellation_markers`, `released_at`), new env vars `KAFKA_TOPIC_ORDER_CANCELLED` / `KAFKA_TOPIC_STOCK_RELEASED`, a "try it" cancellation example
- [ ] T035 [P] Update `CLAUDE.md`: release flow and files, `OrderLock` (always first in both handlers' transactions; lock order order → stock), `OutcomePublisher` as the only publish path, markers, `ProcessedOutcome` values, ADR 0005
- [ ] T036 In `../ecommerce-infra`, from an up-to-date `main`, create `chore/US-02-inventory-release-events`, apply the three edits of [contracts/kafka-events.md](contracts/kafka-events.md#changes-required-in-ecommerce-infradocseventsmd) to `docs/events.md`, and commit (`docs: mark inventory release events as implemented`); do not push without the user's go-ahead (FR-018)
- [ ] T037 Review coverage in `target/site/jacoco/index.html` (every `domain` and `application` package ≥ 80%); add unit tests for any gap
- [ ] T038 Run the manual scenarios A–F of [quickstart.md](quickstart.md) against ecommerce-infra and record the results
- [ ] T039 Final clean gate: `.\mvnw.cmd -B clean verify`

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2 → stories.** Phase 2 changes US-01 code (processed events, outcome lookup,
  publisher, order lock, parser); every US-01 test must stay green there.
- **US1 (Phase 3)** depends on Phase 2. **US2** depends on US1 (same service and domain method).
  **US3** depends on US1 (service) and T013 (markers). **US4** depends on US1. **US5** depends on US1.
- **Polish** after all stories. T036 needs the US-01 infra PR merged (it is).

Key task dependencies: T003 → T004, T012, T013; T005 → T006, T019; T007 → T019; T008 → T009, T010,
T019; T011 → T018; T012 + T016 → T019 → T020, T021 → T022; T019 → T024, T026, T030; T013 → T026, T027.

## Parallel Examples

```text
# Phase 2 after T003:
T004 ReleaseSchemaMigrationIT | T008 OrderLock | T011 StrictJsonParser | T013 markers

# Story 1, tests first:
T014 ReservationReleaseTest | T015 factory test
# then:
T016 domain → (T017 messages | T018 DTO/parser in parallel) → T019 service

# Polish:
T033 ADR | T034 README | T035 CLAUDE.md
```

## Implementation Strategy

1. **MVP**: Phases 1–3 (T001–T022): cancellations release stock end to end.
2. Then US2 (ignore), US3 (race rule; the most important after the MVP), US4, US5.
3. Polish: ADR 0005, docs, contract PR, coverage, manual scenarios, clean build.

All stories ship in one PR for `feature/US-02-release-stock`; phases are commit and review checkpoints.
