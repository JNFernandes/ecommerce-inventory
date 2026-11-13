---

description: "Task list for US-01 Reserve Stock When an Order Is Placed"
---

# Tasks: Reserve Stock When an Order Is Placed (US-01)

**Input**: Design documents from `specs/001-reserve-stock/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/kafka-events.md](contracts/kafka-events.md),
[quickstart.md](quickstart.md)

**Tests**: REQUIRED. Constitution Principles VI and VII make unit tests for every domain and
application method (happy path + error/edge case), integration tests on real PostgreSQL and
component tests on real PostgreSQL + Kafka mandatory. Within each story, write the tests first and
make sure they fail before implementing.

**Organization**: Tasks are grouped by the spec's user stories (Story 1–6 → `[US1]`–`[US6]`).
Note: `[USn]` labels refer to the stories inside this spec, not to the project backlog ids
(`US-01` is this whole feature, `US-02` release, `US-03` retry worker).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Spec user story the task belongs to
- Paths are relative to the repository root. Abbreviations used in paths:
  - `M/` = `src/main/java/com/ecommerce/inventory/`
  - `T/` = `src/test/java/com/ecommerce/inventory/`
  - `R/` = `src/main/resources/`

## Rules that apply to every task

- **Quality gate after every task** (constitution X), in order, with JDK 21 on `JAVA_HOME`:
  `.\mvnw.cmd -B -q compile`, `.\mvnw.cmd -B -q checkstyle:check`, `.\mvnw.cmd -B -q spotless:check`
  (fix with `.\mvnw.cmd -B -q spotless:apply`), `.\mvnw.cmd -B verify`. Fix failures before the
  next task. Never commit a failing gate.
- Every new package gets a `package-info.java`; every public class, method and record component
  gets Javadoc (Checkstyle enforces it).
- `domain` imports nothing from Spring, `jakarta.persistence` or Kafka.
- Public methods never return `null`; use `Optional`. No raw types. No catching `Throwable`.
- Test names describe behaviour via `@DisplayName("should ...")`. Unit tests are `*Test`;
  tests needing containers are `*IT`.
- Commit after each task or small group with Conventional Commits, scope `US-01`
  (e.g. `feat(US-01): add reservation aggregate`, `test(US-01): ...`).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration, package skeleton and test support used by every story.

- [X] T001 Extend `R/application.yml`: add `inventory.kafka.topics.order-placed: ${KAFKA_TOPIC_ORDER_PLACED:orders.order-placed}`, `stock-reserved: ${KAFKA_TOPIC_STOCK_RESERVED:inventory.stock-reserved}`, `stock-rejected: ${KAFKA_TOPIC_STOCK_REJECTED:inventory.stock-rejected}`; `inventory.kafka.consumer-retry` (`max-retries: 3`, `initial-interval: 1s`, `multiplier: 2.0`, `max-interval: 10s`); `inventory.kafka.publish-timeout: 15s`; `inventory.outbound-dead-letter.first-retry-delay: 30s`; `inventory.stock.lock-timeout: 5s`; and producer properties `delivery.timeout.ms: 15000`, `request.timeout.ms: 5000`; change `spring.kafka.listener.ack-mode` from `manual` to `manual_immediate` (required for `DefaultErrorHandler.setCommitRecovered(true)`, which Spring Kafka ignores for any other ack mode) (research R6, R7, R2, R10)
- [X] T002 Create `M/infrastructure/config/InventoryProperties.java`: a `@ConfigurationProperties("inventory") @Validated` record with nested records `Kafka(Topics topics, ConsumerRetry consumerRetry, Duration publishTimeout)`, `Topics(@NotBlank orderPlaced, stockReserved, stockRejected)`, `ConsumerRetry(@Min(0) int maxRetries, @NotNull Duration initialInterval, @DecimalMin("1.0") double multiplier, @NotNull Duration maxInterval)`, `OutboundDeadLetter(@NotNull Duration firstRetryDelay)`, `Stock(@NotNull Duration lockTimeout)`; register it with `@ConfigurationPropertiesScan` on `M/InventoryApplication.java`
- [X] T003 [P] Create `M/infrastructure/config/ClockConfiguration.java` (`@Bean Clock clock()` returning `Clock.systemUTC()`) and `M/infrastructure/config/TransactionConfiguration.java` (`@Bean TransactionOperations transactionOperations(PlatformTransactionManager tm)` returning a `TransactionTemplate`), plus `M/infrastructure/config/package-info.java`. Note: no `TransactionOperations` bean was added; Spring Boot already auto-configures a `TransactionTemplate` (which implements `TransactionOperations`), and a second bean would only replace it
- [X] T004 [P] Create `package-info.java` files describing responsibility for the new packages: `M/domain/model/`, `M/domain/event/`, `M/domain/exception/`, `M/domain/repository/`, `M/application/port/`, `M/messaging/dto/`, `M/infrastructure/persistence/`, `M/infrastructure/kafka/message/`
- [X] T005 [P] Create test support in `T/support/`: `PostgresTestContainer.java` (singleton `PostgreSQLContainer<?>` `postgres:16-alpine`, started once per JVM), `KafkaTestContainer.java` (singleton `org.testcontainers.kafka.ConfluentKafkaContainer` `confluentinc/cp-kafka:7.6.1`), `AbstractPostgresIT.java` (`@SpringBootTest` + `@ServiceConnection` PostgreSQL, Kafka bootstrap set to `localhost:1`, `@MockitoBean OutboundMessagePublisher` is NOT added here), `AbstractComponentIT.java` (PostgreSQL + Kafka via `@ServiceConnection`, creates the three topics with an `AdminClient` in `@BeforeAll`, truncates all tables except `flyway_schema_history` before each test), `TestKafkaClient.java` (send a raw `String` value with a key to a topic; poll a topic for records whose JSON `orderId` matches, using Awaitility), `StockFixtures.java` (insert/read `stock_items` rows with `JdbcTemplate`). Update `T/ApplicationHealthIT.java` to reuse `PostgresTestContainer`

**Checkpoint**: Gate green; application still boots with no tables beyond V1.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, value objects, ports, persistence adapters, the inbound parser and the
publisher. Every story depends on these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Schema

- [X] T006 Create `R/db/migration/V2__reserve_stock_schema.sql` with the tables, constraints and index exactly as in [data-model.md §3](data-model.md): `stock_items` (CHECK `available_quantity >= 0`), `reservations` (UNIQUE `order_id`, status CHECK, reason-iff-rejected CHECK), `reservation_items` (PK, FK cascade, quantity/failure CHECKs), `outcome_messages` (PK `event_id`, UNIQUE + FK `reservation_id`), `processed_events` (PK `event_id`, outcome CHECK, FK `reservation_id`), `outbound_dead_letters` (UNIQUE `event_id`, status CHECK, index `(status, next_retry_at)`), `consumed_dead_letters` (UNIQUE `(topic, partition, record_offset)`)
- [X] T007 [P] Write `T/infrastructure/persistence/SchemaMigrationIT.java` (extends `AbstractPostgresIT`, `JdbcTemplate`): should apply V1 and V2; should refuse a negative `available_quantity` (FR-009); should refuse a second reservation with the same `order_id` (FR-013); should refuse a `REJECTED` reservation without reason and a `RESERVED` one with a reason; should refuse a second `consumed_dead_letters` row for the same topic/partition/offset

### Domain building blocks

- [X] T008 [P] Create domain exceptions in `M/domain/exception/`: `InventoryDomainException` (abstract, extends `RuntimeException`), `InvalidQuantityException`, `InvalidProductIdException`, `InsufficientStockException` (carries `ProductId`, requested, available)
- [X] T009 [P] Create value objects in `M/domain/model/`: `ProductId` (record `String value`; non-null, non-blank, ≤ 64 chars else `InvalidProductIdException`; `Comparable<ProductId>` by value), `OrderId` (record `UUID value`, non-null), `ReservationId` (record `UUID value`, non-null, `static newId()`), `Quantity` (record `long value`, `> 0` else `InvalidQuantityException`; `plus(Quantity)` adds the `long` values; inbound quantities are `int`, so consolidated sums cannot overflow)
- [X] T010 [P] Write `T/domain/model/ValueObjectsTest.java`: valid and invalid cases for each value object (blank/oversized product id, null ids, zero/negative quantity, `plus` of two `Integer.MAX_VALUE` quantities returns the exact `long` sum), product id comparison is case-sensitive
- [X] T011 [P] Create `M/domain/model/ReservationStatus.java` (`RESERVED`, `REJECTED`) and `M/domain/model/RequestedItem.java` (record `ProductId productId`, `Quantity quantity`)
- [X] T012 Create `M/domain/model/RequestedItems.java`: record holding an unmodifiable `List<RequestedItem>` sorted by `productId`; `static RequestedItems consolidate(List<RequestedItem> items)` sums quantities per product (FR-004), rejects an empty list with `InvalidQuantityException`; `Set<ProductId> productIds()`
- [X] T013 [P] Write `T/domain/model/RequestedItemsTest.java`: should merge duplicate products (2 + 3 → 5); should sort by product id; should keep distinct products; should reject an empty list; should consolidate quantities whose sum exceeds `Integer.MAX_VALUE` without error
- [X] T014 Create `M/domain/model/StockItem.java` (aggregate: `ProductId productId`, `int availableQuantity` ≥ 0 checked in constructor; `boolean canSupply(Quantity)`; `void reserve(Quantity)` throws `InsufficientStockException` if it would go negative; `int availableQuantity()`)
- [X] T015 [P] Write `T/domain/model/StockItemTest.java`: should supply when available ≥ requested; should allow reaching zero; should throw `InsufficientStockException` and keep quantity unchanged when requested > available; should refuse negative initial quantity
- [X] T016 [P] Create the repository port `M/domain/repository/StockItemRepository.java` (`Map<ProductId, StockItem> lockAllOrderedByProductId(Set<ProductId> ids)`, `void saveAll(Collection<StockItem> items)`). `ReservationRepository` is created with `Reservation` in T031

### Application ports

- [X] T017 [P] Create application ports in `M/application/port/`: `OutboundMessage` (record `UUID eventId`, `String topic`, `String key`, `String payload`), `OutboundMessageFactory` (`OutboundMessage create(InventoryDomainEvent event)`), `OutboundMessagePublisher` (`void publish(OutboundMessage message)` throws `PublishFailedException`), `PublishFailedException` (unchecked, wraps cause), `OutcomeMessageRepository` (`void save(ReservationId id, OutboundMessage message)`, `Optional<OutboundMessage> findByReservationId(ReservationId id)`), `ProcessedOutcome` enum (`RESERVATION_DECIDED`, `DUPLICATE_ORDER_IGNORED`), `ProcessedEvent` (record `UUID eventId`, `String topic`, `ProcessedOutcome outcome`, `ReservationId reservationId`, `Instant processedAt`), `ProcessedEventRepository` (`Optional<ProcessedEvent> find(UUID eventId)`, `void save(ProcessedEvent event)`), `OutboundDeadLetterRepository` (`void recordFailure(OutboundMessage message, String error, Instant nextRetryAt)`)

### Persistence adapters

- [X] T018 Create JPA entities and Spring Data repositories in `M/infrastructure/persistence/`: `StockItemEntity` (`stock_items`), `ReservationEntity` (`reservations`, `@OneToMany(cascade = ALL, orphanRemoval = true)` to `ReservationItemEntity`), `ReservationItemEntity` (`reservation_items`, `@EmbeddedId` `ReservationItemId`), `OutcomeMessageEntity` (`outcome_messages`, `payload` as `String` with `@JdbcTypeCode(SqlTypes.JSON)`), `ProcessedEventEntity` (`processed_events`), and `StockItemJpaRepository`, `ReservationJpaRepository`, `OutcomeMessageJpaRepository`, `ProcessedEventJpaRepository`. Enum-like columns are mapped with `@Enumerated(EnumType.STRING)` to domain/application enums or stored strings; entities are package-private where possible
- [X] T019 Create `M/infrastructure/persistence/StockItemRepositoryAdapter.java` implementing `StockItemRepository`: `lockAllOrderedByProductId` first runs `SET LOCAL lock_timeout = '<ms>'` from `InventoryProperties.stock().lockTimeout()` via `EntityManager.createNativeQuery(...).executeUpdate()`, then `StockItemJpaRepository.findAllByProductIdInOrderByProductIdAsc` annotated `@Lock(LockModeType.PESSIMISTIC_WRITE)`; maps to `StockItem` in a `LinkedHashMap`; `saveAll` updates `available_quantity` and `updated_at`. Mapping lives in `M/infrastructure/persistence/StockItemMapper.java` (research R1, R2)
- [X] T020 [P] Create `M/infrastructure/persistence/OutcomeMessageRepositoryAdapter.java` and `M/infrastructure/persistence/ProcessedEventRepositoryAdapter.java` implementing the application ports
- [X] T021 [P] Write `T/infrastructure/persistence/StockItemRepositoryAdapterIT.java`: should return only existing products ordered by product id; should persist decreased quantities; should hold the row lock until commit (second transaction with `lock_timeout` 200 ms fails while the first holds the lock); should fail with a lock-timeout exception instead of waiting forever
- [X] T022 [P] Write `T/infrastructure/persistence/OutcomeAndProcessedEventAdaptersIT.java`: should save and read back an outcome message with identical payload JSON; should save and find a processed event; should return `Optional.empty()` for unknown ids

### Inbound parsing and outbound publishing

- [X] T023 [P] Create `M/messaging/dto/OrderPlacedItem.java` (record `@NotBlank @Size(max = 64) String productId`, `@NotNull @Positive Integer quantity`, `BigDecimal unitPrice` ignored) and `M/messaging/dto/OrderPlacedMessage.java` (record `@NotNull UUID eventId`, `@NotNull Instant occurredAt`, `@NotNull UUID aggregateId`, `@NotNull Integer version`, `UUID customerId`, `@NotEmpty List<@Valid @NotNull OrderPlacedItem> items`, `BigDecimal totalAmount`; `@AssertTrue boolean isSupportedVersion()` → `version == 1`), Javadoc on every component per [contracts](contracts/kafka-events.md#consumed-ordersorder-placed)
- [X] T024 Create `M/messaging/InvalidMessageException.java` and `M/messaging/OrderPlacedMessageParser.java` (`@Component`): builds a dedicated `ObjectReader` from the Spring `ObjectMapper` copy with `MapperFeature.ALLOW_COERCION_OF_SCALARS` disabled, `DeserializationFeature.ACCEPT_FLOAT_AS_INT` disabled, `FAIL_ON_UNKNOWN_PROPERTIES` disabled; `OrderPlacedMessage parse(String raw)` throws `InvalidMessageException` on null/blank input, JSON errors or any Bean Validation violation (message lists the violations) (research R5, FR-002, FR-003)
- [X] T025 [P] Write `T/messaging/OrderPlacedMessageParserTest.java` (plain `Validation.buildDefaultValidatorFactory()` + `JsonMapper` with `JavaTimeModule`): should parse the contract example; should ignore `customerId`, `unitPrice`, `totalAmount` and unknown fields; should reject non-JSON, missing `eventId`, empty `items`, blank `productId`, quantity `0`, quantity `"5"`, quantity `5.5`, `version` 2
- [X] T026 Create `M/infrastructure/kafka/KafkaOutboundMessagePublisher.java` implementing `OutboundMessagePublisher`: `kafkaTemplate.send(topic, key, payload).get(publishTimeout)`; wraps `ExecutionException`, `TimeoutException`, `KafkaException` in `PublishFailedException`; on `InterruptedException` restores the interrupt flag and throws `PublishFailedException`; logs success at DEBUG with topic, key and event id (research R7)
- [X] T027 [P] Write `T/infrastructure/kafka/KafkaOutboundMessagePublisherTest.java` (Mockito `KafkaTemplate`): should send with key and payload; should throw `PublishFailedException` when the future fails, times out, or `send` throws

**Checkpoint**: Gate green. Schema, value objects, ports, adapters, parser and publisher exist and
are tested; no listener yet.

---

## Phase 3: User Story 1 — Reserve all items of an order that can be fully supplied (P1) 🎯 MVP

**Goal**: An order whose items are all known and available is reserved end to end: stock
decreases, a `RESERVED` reservation, its outcome message and the processed event are saved in one
transaction, then `inventory.stock-reserved` is published and the offset acknowledged.

**Independent Test**: Seed SKU-001 = 10 and SKU-002 = 25, produce the contract example order with
3 × SKU-001 and 5 × SKU-002 to `orders.order-placed`; expect SKU-001 = 7, SKU-002 = 20, one
`RESERVED` reservation and one `inventory.stock-reserved` message keyed by the reservation id.

### Tests for User Story 1 (write first, must fail)

- [X] T028 [P] [US1] Write `T/domain/model/ReservationReserveTest.java`: should reserve every item and decrease each stock item; should allow reserving the exact available quantity (stock → 0); should reserve the consolidated quantity for duplicate lines; should raise exactly one `StockReserved` event with reservation id, order id, items and `reservedAt = now`; `pullDomainEvents()` should return the events once and then an empty list
- [X] T029 [P] [US1] Write `T/infrastructure/kafka/JsonOutboundMessageFactoryTest.java` (reserved case): JSON has exactly `eventId`, `occurredAt`, `aggregateId`, `version`, `orderId`, `items[{productId, quantity}]`, `reservedAt`; `aggregateId` and key equal the reservation id; `version` 1; timestamps are ISO 8601 UTC strings; `occurredAt == reservedAt`; topic is `inventory.stock-reserved`

### Implementation for User Story 1

- [X] T030 [US1] Create `M/domain/event/InventoryDomainEvent.java` (sealed interface: `ReservationId reservationId()`, `OrderId orderId()`, `Instant occurredAt()`; permits `StockReserved`) and `M/domain/event/StockReserved.java` (record `ReservationId reservationId`, `OrderId orderId`, `List<RequestedItem> items`, `Instant reservedAt`)
- [X] T031 [US1] Create `M/domain/model/Reservation.java`: fields per [data-model.md §1](data-model.md); `static Reservation decide(ReservationId id, OrderId orderId, RequestedItems items, Map<ProductId, StockItem> lockedStock, Instant now)`; in this story it reserves every item via `StockItem.reserve` (an unknown or insufficient item surfaces as `InsufficientStockException`; the rejection path is added in T042); `static Reservation restore(...)` without events; `List<InventoryDomainEvent> pullDomainEvents()`; getters return immutable values / `Optional`. Also create the port `M/domain/repository/ReservationRepository.java` (`Optional<Reservation> findByOrderId(OrderId id)`, `void save(Reservation reservation)`)
- [X] T032 [P] [US1] Create `M/infrastructure/kafka/message/StockReservedMessage.java` (record `UUID eventId`, `Instant occurredAt`, `UUID aggregateId`, `int version`, `UUID orderId`, `List<ReservedItem> items`, `Instant reservedAt`) and `M/infrastructure/kafka/message/ReservedItem.java` (record `String productId`, `long quantity`), Javadoc on every component matching `events.md`
- [X] T033 [US1] Create `M/infrastructure/kafka/JsonOutboundMessageFactory.java` implementing `OutboundMessageFactory` with an exhaustive `switch` over the sealed `InventoryDomainEvent` (reserved case now), new random `eventId`, key and `aggregateId` = reservation id, topic from `InventoryProperties`, payload serialized with the Spring `ObjectMapper` (research R8)
- [X] T034 [US1] Create `M/infrastructure/persistence/ReservationRepositoryAdapter.java` and `M/infrastructure/persistence/ReservationMapper.java` implementing `ReservationRepository` for `RESERVED` reservations (items without failure columns); add `T/infrastructure/persistence/ReservationRepositoryAdapterIT.java`: should save and find by order id a reserved reservation with its items; should return empty for an unknown order
- [X] T035 [US1] Create `M/application/ReserveStockCommand.java` (record `UUID eventId`, `String topic`, `OrderId orderId`, `List<RequestedItem> items`) and `M/application/ReserveStockOutcome.java` (sealed interface; record `Decided(ReservationId reservationId, ReservationStatus status)`)
- [X] T036 [US1] Create `M/application/ReserveStockService.java` (`@Service`, no `@Transactional`): `ReserveStockOutcome reserve(ReserveStockCommand cmd)` → inside `transactionOperations.execute(...)`: consolidate items, `stockItemRepository.lockAllOrderedByProductId`, `Reservation.decide(ReservationId.newId(), ..., clock.instant())`, `stockItemRepository.saveAll` (only when RESERVED), `reservationRepository.save`, map the pulled event with `OutboundMessageFactory`, `outcomeMessageRepository.save`, `processedEventRepository.save(RESERVATION_DECIDED)`; after the block returns, `publisher.publish(outbound)`; log INFO with order id, reservation id, status, event ids (research R3)
- [X] T037 [US1] Write `T/application/ReserveStockServiceTest.java` (Mockito; `TransactionOperations` stub that runs the callback and records begin/end): should save stock, reservation, outcome message and processed event inside the transaction and publish after it (`InOrder`); should not publish when the transaction throws; should pass the consolidated, sorted product ids to the lock call
- [X] T038 [US1] Create `M/messaging/OrderPlacedListener.java` (`@Component`): `@KafkaListener(id = "order-placed-listener", topics = "${inventory.kafka.topics.order-placed}")` method `onMessage(ConsumerRecord<String, String> record, Acknowledgment ack)` → `parser.parse(record.value())` → map to `ReserveStockCommand` (`aggregateId` → `OrderId`, items → `RequestedItem`) → `service.reserve(...)` → `ack.acknowledge()`. No business logic; exceptions propagate to the container error handler
- [X] T039 [P] [US1] Write `T/messaging/OrderPlacedListenerTest.java` (Mockito): should map the message to a command and acknowledge after the service returns; should not acknowledge when the service throws; should not call the service and rethrow `InvalidMessageException` for an invalid message
- [X] T040 [US1] Write `T/component/ReserveStockComponentIT.java` (extends `AbstractComponentIT`) happy-path tests: should reserve and publish `inventory.stock-reserved` for an order of 3 × SKU-001 and 5 × SKU-002 (stock 7 and 20, one `RESERVED` row, one `outcome_messages` row, one `processed_events` row, record key = `aggregateId` = reservation id, payload matches the stored outcome; the outcome is awaited with `await().atMost(Duration.ofSeconds(2))` measured from the produce call, and the `@DisplayName` cites SC-001); should consolidate duplicate lines (2 + 3 × SKU-001 → one item quantity 5); should allow an exact-stock order (SKU-004 5 → 0)

**Checkpoint**: Story 1 works end to end; gate green. MVP.

---

## Phase 4: User Story 2 — Reject an order that cannot be fully supplied (P1)

**Goal**: Unknown or insufficient items produce a `REJECTED` reservation with reason and failing
items, no stock change, and one `inventory.stock-rejected` message.

**Independent Test**: Seed SKU-001 = 10 and SKU-004 = 5; produce an order for 2 × SKU-001 and
6 × SKU-004; expect no stock change, a `REJECTED` reservation and one stock-rejected message with
reason `insufficient stock` and failing item SKU-004 requested 6 available 5.

### Tests for User Story 2 (write first, must fail)

- [X] T041 [P] [US2] Write `T/domain/model/ReservationRejectTest.java` and `T/domain/model/RejectionReasonTest.java`: should reject with `insufficient stock` and list only the insufficient item (requested/available); should treat available 0 as insufficient, not unknown; should reject with `unknown product` and available 0; should reject with `unknown product and insufficient stock` listing both, sorted by product id; should use the consolidated requested quantity (6 + 7 = 13 vs 10); should reject as `insufficient stock` (not throw) an order whose consolidated quantity exceeds `Integer.MAX_VALUE`; should leave every `StockItem` untouched; should raise exactly one `StockRejected`; `RejectionReason.from` maps every non-empty kind set and refuses an empty set; `wireValue()` texts are exact

### Implementation for User Story 2

- [X] T042 [US2] Add `M/domain/model/FailureKind.java`, `M/domain/model/ItemFailure.java` (record; `available == 0` required for `UNKNOWN_PRODUCT`), `M/domain/model/RejectionReason.java` (enum with `wireValue()` and `static from(Set<FailureKind>)`), `M/domain/model/Rejection.java` (record `RejectionReason reason`, `List<ItemFailure> failures` sorted by product id), `M/domain/event/StockRejected.java` (record `ReservationId`, `OrderId`, `RejectionReason reason`, `List<ItemFailure> failures`, `Instant rejectedAt`) and add it to the `permits` of `InventoryDomainEvent`; extend `Reservation.decide` to compute failures first and reject without touching stock (FR-005 to FR-008); add `Optional<Rejection> rejection()` to `Reservation`
- [X] T043 [P] [US2] Create `M/infrastructure/kafka/message/StockRejectedMessage.java` (record `eventId`, `occurredAt`, `aggregateId`, `version`, `UUID orderId`, `String reason`, `List<RejectedItem> items`, `Instant rejectedAt`) and `M/infrastructure/kafka/message/RejectedItem.java` (record `String productId`, `long requested`, `int available`), Javadoc per `events.md`
- [X] T044 [US2] Extend `M/infrastructure/kafka/JsonOutboundMessageFactory.java` with the `StockRejected` case (topic `inventory.stock-rejected`, `reason` = `wireValue()`, only failing items) and add the rejected cases to `T/infrastructure/kafka/JsonOutboundMessageFactoryTest.java` (exact field set, `occurredAt == rejectedAt`)
- [X] T045 [US2] Extend `M/infrastructure/persistence/ReservationMapper.java` / `ReservationRepositoryAdapter.java` to store `rejection_reason` and, per item, `failure_kind` and `available_at_decision` (null for non-failing items), and restore them; add rejected cases to `T/infrastructure/persistence/ReservationRepositoryAdapterIT.java` (round trip with unknown and insufficient items)
- [X] T046 [US2] Extend `T/application/ReserveStockServiceTest.java`: should not call `stockItemRepository.saveAll` for a rejected reservation but still save reservation, outcome message and processed event, then publish; should return `Decided(id, REJECTED)`
- [X] T047 [US2] Add rejection tests to `T/component/ReserveStockComponentIT.java`: insufficient stock (SKU-001 2 + SKU-004 6), zero stock (SKU-005), unknown product (SKU-999), mixed reason, consolidated over-request (SKU-001 6 + 7); each asserts unchanged stock, one `REJECTED` row and the exact stock-rejected payload; also assert `consumed_dead_letters` is empty and `ReserveStockService.reserve` was invoked exactly once per message (`@MockitoSpyBean`), proving rejections are not retried (FR-025)

**Checkpoint**: Stories 1 and 2 work; gate green.

---

## Phase 5: User Story 3 — Never oversell under concurrent orders (P1)

**Goal**: Prove and harden concurrent behaviour: no overselling, no deadlock, a concurrent
duplicate order id does not create a second reservation.

**Independent Test**: 20 concurrent orders of 1 × SKU-004 (5 units) → exactly 5 `RESERVED`,
15 `REJECTED`, final stock 0, 20 outcomes.

### Tests for User Story 3

- [X] T048 [P] [US3] Write `T/application/ConcurrentReservationIT.java` (extends `AbstractPostgresIT`, real `ReserveStockService`, `@MockitoBean OutboundMessagePublisher` recording calls): should reserve exactly 5 of 20 concurrent single-unit orders for a 5-unit product, reject 15 with `insufficient stock`, end at 0 and publish 20 outcomes (run 20 tasks released together with a `CountDownLatch` on a fixed thread pool; repeat 3 times with fresh data) (SC-002); should complete two concurrent orders listing SKU-001/SKU-002 in opposite order without deadlock, repeated 50 times, within a bounded time (FR-011); should never let any `available_quantity` go below 0 (query after each run)
- [X] T049 [P] [US3] Add to `T/application/ConcurrentReservationIT.java`: two concurrent commands with different event ids for the same order id → exactly one reservation row and stock decreased once; the losing call fails with a data-integrity exception (to be retried by the container, see T058)

### Implementation for User Story 3

- [X] T050 [US3] Make any fix the tests reveal, without changing ADR 0002's strategy: lock ordering in `StockItemRepositoryAdapter`, the `SET LOCAL lock_timeout`, and that the transaction (not the caller) sees `DataIntegrityViolationException` on `reservations.order_id` so the whole transaction rolls back. Document the duplicate-order race outcome in a Javadoc paragraph on `M/application/ReserveStockService.java`

**Checkpoint**: Stories 1–3 work; concurrency proven on real PostgreSQL.

---

## Phase 6: User Story 4 — Process each order exactly once despite redeliveries (P2)

**Goal**: A redelivered message (same `eventId`) changes nothing and republishes the stored
outcome once; a new event for an already handled order is ignored and publishes nothing.

**Independent Test**: Produce the same message twice → stock reduced once, two identical
`inventory.stock-reserved` records with the same `eventId`. Produce a new `eventId` for the same
order → nothing changes, nothing published, log line written.

### Tests for User Story 4 (write first, must fail)

- [ ] T051 [P] [US4] Extend `T/application/ReserveStockServiceTest.java`: should republish the stored outcome (same `OutboundMessage`) and not lock stock or save anything when the event id was processed with `RESERVATION_DECIDED`, returning `Redelivered`; should do nothing for an event id processed with `DUPLICATE_ORDER_IGNORED`; should save a `DUPLICATE_ORDER_IGNORED` processed event, not lock stock and not publish when a reservation already exists for the order, returning `DuplicateOrderIgnored(existingId)`

### Implementation for User Story 4

- [ ] T052 [US4] Add `Redelivered(ReservationId reservationId)` and `DuplicateOrderIgnored(ReservationId existingReservationId)` to `M/application/ReserveStockOutcome.java`; in `M/application/ReserveStockService.java` start the transaction with `processedEventRepository.find(eventId)` and `reservationRepository.findByOrderId(orderId)` per research R4; republish the stored outcome after the transaction for `Redelivered`; log at INFO `"Duplicate OrderPlaced ignored"` with order id, new event id and existing reservation id (FR-014 to FR-016)
- [ ] T053 [US4] Add to `T/component/ReserveStockComponentIT.java`: same message twice → stock reduced once, one reservation, two published records with identical `eventId` and payload; new `eventId` for the same order (different items) → no change, no new record within 5 s, one extra `processed_events` row with `DUPLICATE_ORDER_IGNORED`; in both cases assert `consumed_dead_letters` is empty and `ReserveStockService.reserve` was invoked exactly once per message (`@MockitoSpyBean`) (FR-025)

**Checkpoint**: Stories 1–4 work.

---

## Phase 7: User Story 5 — Keep the flow moving when messaging fails (P2)

**Goal**: Publish failures land in `outbound_dead_letters`; invalid messages go straight to
`consumed_dead_letters`; failing valid messages are retried with exponential backoff, then
dead-lettered; offsets are committed so the partition keeps moving.

**Independent Test**: (a) Force the publisher to fail → reservation committed and one `PENDING`
outbound dead-letter row. (b) Produce `{"not":"an order"}` then a valid order → one consumed
dead-letter row (attempts 1) and the valid order reserved.

### Tests for User Story 5 (write first, must fail)

- [ ] T054 [P] [US5] Extend `T/application/ReserveStockServiceTest.java`: when `publish` throws `PublishFailedException`, should call `outboundDeadLetterRepository.recordFailure(message, error, now + firstRetryDelay)` and still return the outcome; when that also throws, should log the full message at ERROR and still return (no exception to the listener); same behaviour for a failing republish on redelivery
- [ ] T055 [P] [US5] Write `T/infrastructure/persistence/DeadLetterAdaptersIT.java`: outbound `recordFailure` inserts `PENDING`, `retry_count` 0, `next_retry_at`, `last_error`; a second failure for the same event id updates `last_error`/`updated_at` without a new row; consumed dead-letter insert stores raw payload (including non-JSON text and null), key, topic, partition, offset, error, attempts; inserting the same topic/partition/offset twice keeps one row

### Implementation for User Story 5

- [ ] T056 [US5] Create `M/infrastructure/persistence/OutboundDeadLetterRepositoryAdapter.java` implementing `OutboundDeadLetterRepository` with a native `INSERT ... ON CONFLICT (event_id) DO UPDATE SET last_error = EXCLUDED.last_error, updated_at = EXCLUDED.updated_at` executed in `REQUIRES_NEW`-equivalent own transaction (via `TransactionOperations` in the service or `@Transactional(propagation = REQUIRES_NEW)` on the adapter); and `M/infrastructure/persistence/ConsumedDeadLetterRepository.java` (infrastructure-only, `JdbcTemplate`, `INSERT ... ON CONFLICT (topic, partition, record_offset) DO NOTHING`)
- [ ] T057 [US5] Update `M/application/ReserveStockService.java`: wrap `publish` (both first publish and republish) in `try/catch (PublishFailedException e)`; log ERROR with topic, key, event id, order id and cause; call `outboundDeadLetterRepository.recordFailure(message, describe(e), clock.instant().plus(firstRetryDelay))`; if that throws a `RuntimeException`, log ERROR including the full payload (FR-023, research R7)
- [ ] T058 [US5] Create `M/infrastructure/kafka/ConsumedDeadLetterRecoverer.java` (`ConsumerRecordRecoverer`): stores the record (raw value, key, topic, partition, offset, root-cause class and message, and `attempts` = 1 when the root cause is `InvalidMessageException` (not retryable), otherwise `consumerRetry.maxRetries + 1` from `InventoryProperties`) and logs ERROR with full context. Extend `M/infrastructure/kafka/KafkaConfiguration.java` with a `DefaultErrorHandler` bean: `ExponentialBackOffWithMaxRetries` from `InventoryProperties.kafka().consumerRetry()`, recoverer above, `addNotRetryableExceptions(InvalidMessageException.class)`, `setCommitRecovered(true)`, `setAckAfterHandle(true)`; register it on the listener container factory via a `ContainerCustomizer` or Boot's auto-configured `CommonErrorHandler` bean (research R6, FR-024)
- [ ] T059 [P] [US5] Write `T/infrastructure/kafka/ConsumedDeadLetterRecovererTest.java` (Mockito repository): should store non-JSON values verbatim; should record the root cause; should record attempts
- [ ] T060 [US5] Add to `T/component/ReserveStockComponentIT.java`: invalid message then valid one → one `consumed_dead_letters` row with attempts 1 and the valid order reserved (SC-006); invalid message as the **last** record on its partition → after it is dead-lettered, `AdminClient.listConsumerGroupOffsets("inventory-service")` shows the committed offset for that partition = record offset + 1 (H1, FR-019); publish failure via `@MockitoSpyBean KafkaOutboundMessagePublisher` throwing once → reservation committed, one `PENDING` outbound dead-letter row with the full payload (SC-007); persistent processing failure (spy on `ReserveStockService` throwing a `RuntimeException`, retry settings shortened via `@TestPropertySource`: 2 retries, 50 ms) → dead-lettered with attempts 3, and the next valid message is processed; transient failure (throws once, then succeeds) → exactly one outcome published

**Checkpoint**: Stories 1–5 work; all failure paths covered.

---

## Phase 8: User Story 6 — Try the flow locally with sample products (P3)

**Goal**: The `local` profile seeds SKU-001..SKU-005 without affecting tests or production.

**Independent Test**: Start with `.\mvnw.cmd spring-boot:run` against ecommerce-infra; the five
products exist with 10/25/100/5/0; restarting does not reset them.

- [ ] T061 [P] [US6] Create `R/db/local/R__seed_local_stock.sql` inserting SKU-001 10, SKU-002 25, SKU-003 100, SKU-004 5, SKU-005 0 with `ON CONFLICT (product_id) DO NOTHING`, and `R/application-local.yml` setting `spring.flyway.locations: classpath:db/migration,classpath:db/local` (research R9, FR-026)
- [ ] T062 [US6] In `pom.xml` configure `spring-boot-maven-plugin` with `<configuration><profiles><profile>local</profile></profiles></configuration>` so `spring-boot:run` uses the `local` profile; Docker and tests are unaffected
- [ ] T063 [P] [US6] Write `T/infrastructure/persistence/LocalSeedIT.java`: with `@ActiveProfiles("local")` the five products exist with the documented quantities; after changing SKU-003 to 42 and re-running Flyway `migrate`, SKU-003 stays 42 and there are still five rows; and add an assertion to `T/infrastructure/persistence/SchemaMigrationIT.java` that without the profile `stock_items` is empty

**Checkpoint**: All six stories work.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T064 [P] Write `docs/adr/0004-outcome-stored-with-reservation.md` (Context / Decision / Consequences: save the outcome message with the reservation, publish after commit, republish on redelivery, outbound dead-letter on failure; relation to US-02/US-03) and add it to the index in `docs/adr/README.md`
- [ ] T065 [P] Update `README.md`: status (US-01 implemented), local sample products table (SKU-001..SKU-005 with quantities) and the `local` profile, Postman/kafka-ui instructions from [quickstart.md](quickstart.md), new tables, new environment variables (`KAFKA_TOPIC_*`)
- [ ] T066 [P] Update `CLAUDE.md`: new packages and their roles, the reservation flow (`ReserveStockService`), tables, idempotency/duplicate-order rules, retry and dead-letter behaviour, seed data and `local` profile
- [ ] T067 In the `ecommerce-infra` repository, on a branch `chore/US-01-inventory-events` created from its `main`, apply the four edits listed in [contracts/kafka-events.md](contracts/kafka-events.md#changes-required-in-ecommerce-infradocseventsmd) to `../ecommerce-infra/docs/events.md` and commit them (`docs: mark inventory reservation events as implemented`); link that PR in this feature's PR description (FR-022)
- [ ] T068 Review coverage in `target/site/jacoco/index.html`: every package under `domain` and `application` ≥ 80% lines; add unit tests for any gap
- [ ] T069 Run the manual scenarios A–D from [quickstart.md](quickstart.md) against ecommerce-infra and record the result in the PR description
- [ ] T070 Final full quality gate (compile, checkstyle, spotless, verify) on a clean build (`.\mvnw.cmd -B clean verify`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: depends on Setup; blocks every story.
- **US1 (Phase 3)**: depends on Foundational. It is the MVP and creates the listener, service,
  factory and reservation aggregate that the other stories extend.
- **US2 (Phase 4)**: depends on US1 (extends `Reservation.decide`, the factory and the mapper).
- **US3 (Phase 5)**: depends on US2 (the 20-order test needs rejections).
- **US4 (Phase 6)**: depends on US1; independent of US2/US3.
- **US5 (Phase 7)**: depends on US1; T060's persistent-failure test benefits from US4 but does
  not require it.
- **US6 (Phase 8)**: depends only on Foundational (T006); can be done any time after Phase 2.
- **Polish (Phase 9)**: after all stories.

### Within Each Story

- Tests first (they must fail), then domain → application → infrastructure → messaging →
  component test.
- Run the quality gate after every task; commit per task or small group.

### Key task dependencies

- T009 → T012 → T014 → T016 → T031 (which also adds `ReservationRepository`).
- T017 → T020, T026, T033, T036. T018 → T019, T020, T034. T023 → T024 → T038.
- T031 → T042 → T045, T044. T036 → T046, T052, T057. T058 → T060.

## Parallel Examples

```text
# Phase 2, after T006:
T007 SchemaMigrationIT        | T008 exceptions | T009 value objects | T011 enums/RequestedItem
T017 application ports        | T023 inbound DTOs

# User Story 1, tests first:
T028 ReservationReserveTest   | T029 JsonOutboundMessageFactoryTest

# User Story 5, tests first:
T054 service failure tests    | T055 DeadLetterAdaptersIT | T059 recoverer unit test

# Any time after Phase 2:
T061 seed SQL + application-local.yml (US6) in parallel with US1 work
```

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 (US1): reservation happy path end to end.
3. Stop and validate with the US1 independent test and quickstart scenario A.

### Incremental Delivery

1. US1 → US2 (rejections; the saga's two outcomes) → US3 (concurrency proof).
2. US4 (idempotency) → US5 (failure paths) → US6 (local seed).
3. Polish: ADR 0004, README, CLAUDE.md, `events.md` PR, coverage, quickstart, final gate.

All stories ship together in one PR for `feature/US-01-reserve-stock`; the phases are review and
commit checkpoints, not separate releases.

## Notes

- `[P]` = different files and no dependency on an incomplete task.
- Each phase ends at a green quality gate.
- Avoid: business logic in the listener or service, `@Transactional` publishing, `Thread.sleep` in
  tests (use Awaitility), new runtime dependencies.
