# Research: Reserve Stock When an Order Is Placed (US-01)

Phase 0 output for [plan.md](plan.md). Each decision resolves an open technical question from the
Technical Context. Constitution references are to `.specify/memory/constitution.md` v1.0.1.

## R1. Locking strategy for all-or-nothing reservation

- **Decision**: Pessimistic row locks taken in ascending `productId` order with a single query
  (`SELECT ... WHERE product_id IN (:ids) ORDER BY product_id FOR UPDATE`, via a Spring Data
  `@Lock(PESSIMISTIC_WRITE)` query), as already decided in ADR 0002. Products absent from the
  result are unknown. The domain decides on the loaded `StockItem`s; only a Reserved outcome
  writes stock.
- **Rationale**: Keeps every business decision (all-or-nothing, unknown vs insufficient, failing
  item details) in the domain (Principle I) and gives deterministic lock order (Principle IV,
  FR-011). In PostgreSQL the `LockRows` node sits above `Sort`, so locks follow `ORDER BY`.
- **Alternatives considered**: conditional `UPDATE ... WHERE available_quantity >= :n` (moves the
  rule into SQL, needs extra queries to tell unknown from insufficient and a second transaction to
  record the rejection); optimistic `@Version` (retry storms on hot products). See ADR 0002.

## R2. Bounding lock waits

- **Decision**: The stock repository adapter executes `SET LOCAL lock_timeout = '<n>ms'`
  (configurable, default 5000 ms) inside the reservation transaction before the locking query. A
  timeout raises an exception that rolls back the transaction and goes through the inbound retry
  path (R6).
- **Rationale**: `SET LOCAL` scopes the setting to the reservation transaction only (not Flyway or
  other queries). Hibernate's `jakarta.persistence.lock.timeout` hint is not honoured by the
  PostgreSQL dialect except for 0 (`NOWAIT`), so it cannot be relied on.
- **Alternatives considered**: global `lock_timeout` via Hikari `connection-init-sql` (affects
  every statement including migrations); `NOWAIT` (fails immediately under normal contention,
  causing needless retries).

## R3. Write flow, atomic save of the outcome, and publish after commit

- **Decision**: `ReserveStockService` (application) does not use `@Transactional`. It runs the
  decision inside an explicit `TransactionOperations` block that saves, in one transaction: the
  stock changes, the reservation, the serialized outcome message (event id, topic, key, JSON
  payload, in `outcome_messages` through an application port, so the domain stays unaware of
  messaging), and the processed-event record. After the block returns (i.e. after commit) the
  service publishes the outcome synchronously (`KafkaTemplate.send(...).get(timeout)`). The
  listener acknowledges the offset only after the service returns.
- **Rationale**: Makes the order "commit → publish → ack" explicit and unit-testable with mocks
  (Principle III). Storing the outcome message with the reservation satisfies FR-016/FR-017: a
  redelivered message can republish the exact original message (same `eventId`) if the service
  stopped between commit and publish. A synchronous send lets the service detect a failure and
  write the outbound dead-letter entry (FR-023).
- **Alternatives considered**: `@TransactionalEventListener(AFTER_COMMIT)` (publishing becomes
  implicit and a failure there is hard to route to the dead-letter table and hard to unit test);
  a full transactional outbox with a relay (it is effectively what US-03 adds for failures; a
  relay for every message adds latency and is out of scope); Kafka transactions (do not span the
  database).

## R4. Idempotency and duplicate orders

- **Decision**:
  - `processed_events` (PK `event_id`) is written in the same transaction as the reservation. It
    records the outcome kind (`RESERVATION_DECIDED` or `DUPLICATE_ORDER_IGNORED`) and the
    reservation id.
  - The service first checks `processed_events`. If the event id is known and the kind is
    `RESERVATION_DECIDED`, it republishes the stored outcome message of that reservation once
    (FR-016) and changes nothing. If the kind is `DUPLICATE_ORDER_IGNORED`, it does nothing.
  - Otherwise it checks `reservations` by `order_id` (unique). If one exists, it records a
    `DUPLICATE_ORDER_IGNORED` processed event, logs the order id, the new event id and the
    existing reservation id, and publishes nothing (FR-014).
  - Two messages for the same order processed concurrently (different partitions) are serialized
    by the unique constraint on `reservations.order_id`: the loser's insert fails, its transaction
    rolls back (no stock change), it is retried (R6) and on retry takes the duplicate-order path.
- **Rationale**: Both checks are inside the database transaction, so they hold across instances.
  Messages with the same key are on the same partition and are not processed concurrently, so the
  `event_id` PK only needs to be a safety net.
- **Alternatives considered**: deduplicating in memory (lost on restart, not shared across
  instances); deduplicating on `orderId` only (would not allow republishing on redelivery, and
  would conflate distinct events).

## R5. Consuming and validating `orders.order-placed`

- **Decision**: The consumer receives the raw value as a `String` (`StringDeserializer`). The
  listener parses it with a dedicated Jackson `ObjectReader` into an immutable record
  `OrderPlacedMessage` (nested `OrderPlacedItem`) and validates it with Jakarta Bean Validation
  (`@NotNull`, `@NotEmpty`, `@NotBlank`, `@Positive`, `@Valid`, `version` must equal 1). The reader
  disables scalar coercion (`MapperFeature.ALLOW_COERCION_OF_SCALARS`) and
  `DeserializationFeature.ACCEPT_FLOAT_AS_INT`, so `"5"` or `5.5` as a quantity is invalid, and
  ignores unknown properties (additive changes). Any parse or validation failure throws
  `InvalidMessageException`, which is non-retryable (R6).
- **Rationale**: Parsing in the listener (instead of a Kafka deserializer) keeps the raw text
  available for the inbound dead-letter entry and makes invalid input an ordinary, testable code
  path (Principle V: never partially processed).
- **Alternatives considered**: `JsonDeserializer` + `ErrorHandlingDeserializer` (works, but the
  raw payload is only available as headers and the validation still has to happen in the
  listener); Jackson defaults (silently truncate `5.5` to `5`).

## R6. Inbound retries and the consumed-message dead-letter table

- **Decision**: Spring Kafka `DefaultErrorHandler` on the listener container factory with
  `ExponentialBackOffWithMaxRetries(3)` (initial 1 s, multiplier 2, max 10 s; configurable), a
  custom `ConsumerRecordRecoverer` that inserts into `consumed_dead_letters`
  (`ON CONFLICT (topic, partition, record_offset) DO NOTHING`) and logs with full context, and
  `setCommitRecovered(true)` so the offset is committed after recovery. This requires the
  container ack mode `MANUAL_IMMEDIATE`: Spring Kafka ignores `commitRecovered` for any other ack
  mode (verified in spring-kafka 3.3.16 `SeekUtils`: "'commitRecovered' ignored, container AckMode
  must be MANUAL_IMMEDIATE"). With plain `MANUAL`, a dead-lettered record that is the last one on
  its partition would never have its offset committed and would be reprocessed after a restart.
  The recoverer computes `attempts` as 1 for `InvalidMessageException` (not retryable) and
  `maxRetries + 1` otherwise.
  `InvalidMessageException` is registered as not retryable (dead-lettered on the first failure).
  If the recoverer itself fails (database down), the record is re-seeked and retried later, so it
  is never lost.
- **Rationale**: Built-in, well-tested backoff and recovery, matching Principle VIII (retry with
  exponential backoff, then dead-letter and commit so the partition is not blocked). Business
  rejections are normal returns, not exceptions, so they are never retried (FR-025).
- **Alternatives considered**: `DeadLetterPublishingRecoverer` to a `.DLT` topic (the constitution
  requires a table); non-blocking retry topics (`@RetryableTopic`; needs extra topics that
  ecommerce-infra does not create and breaks per-key ordering).

## R7. Producer configuration and publish failures

- **Decision**: `KafkaTemplate<String, String>` with the reservation id as key and the JSON
  payload as value; idempotent producer, `acks=all`, `max.block.ms` 10 s (already configured),
  `delivery.timeout.ms` 15 s, `request.timeout.ms` 5 s. The publisher port wraps send failures
  (exceptions or a timed-out `get`) in `PublishFailedException`. The application service catches
  it, logs topic, key, event id, order id and cause, and calls
  `OutboundDeadLetterRepository.recordFailure(...)` in its own transaction (status `PENDING`,
  `retry_count` 0, `next_retry_at` = now + 30 s configurable, `last_error`). An insert for an event
  id that already has an entry updates `last_error` and `updated_at` instead (`ON CONFLICT`). If
  that write also fails, the full message is logged at ERROR so it can be recovered manually; the
  offset is still acknowledged because the reservation is committed.
- **Rationale**: FR-023 and Principle VIII; bounded blocking keeps the consumer within
  `max.poll.interval.ms` even when Kafka is down after the commit.
- **Alternatives considered**: asynchronous send with a callback (the offset could be acked before
  knowing whether the publish failed, and the dead-letter write would happen on a producer
  thread).

## R8. JSON payload shape, timestamps and contract fidelity

- **Decision**: Outbound messages are Java records (`StockReservedMessage`,
  `StockRejectedMessage`, with nested item records) serialized by the Spring-managed `ObjectMapper`
  (`WRITE_DATES_AS_TIMESTAMPS` disabled, Boot default), so `Instant`s are ISO 8601 UTC strings.
  `occurredAt` and `reservedAt` / `rejectedAt` equal the decision time from an injected `Clock`.
  The serialized JSON is stored in `outcome_messages.payload` (`jsonb`) and republished
  verbatim. Contract tests compare the JSON field set to [contracts/](contracts/).
- **Rationale**: Records give immutable, Javadoc-documented contract classes (Principle VIII). An
  injectable `Clock` makes timestamps deterministic in tests.
- **Alternatives considered**: building JSON with `ObjectNode` (no compile-time shape);
  re-serializing from the domain on republish (could drift from what was first sent).

## R9. Local-only seed data

- **Decision**: A Flyway **repeatable** migration `R__seed_local_stock.sql` in
  `src/main/resources/db/local/`, added to `spring.flyway.locations` only by the `local` Spring
  profile (`application-local.yml`). It uses `INSERT ... ON CONFLICT (product_id) DO NOTHING`.
  `./mvnw spring-boot:run` activates the `local` profile through the `spring-boot-maven-plugin`
  configuration; Docker, tests and production do not.
- **Rationale**: Not part of the production migration location (`db/migration`), never runs in
  tests, never resets or duplicates stock (FR-026), and runs after the schema migrations because
  repeatable migrations are applied after versioned ones.
- **Alternatives considered**: `spring.sql.init` data scripts (ordering relative to Flyway and
  re-running on every start need extra configuration); a versioned migration with a profile check
  (would land in the production history).

## R10. Topic names and listener wiring

- **Decision**: Topic names come from configuration (`inventory.kafka.topics.order-placed`,
  `.stock-reserved`, `.stock-rejected`) bound to a validated `@ConfigurationProperties` record,
  with defaults equal to the names in `events.md`. The listener has an explicit id
  (`order-placed-listener`) and is started by the existing `KafkaListenerStarter`. Topics are not
  created by the service (ecommerce-infra's `kafka-init` creates them); component tests create
  them with an `AdminClient`.
- **Rationale**: Keeps the startup-without-Kafka guarantee from the bootstrap and allows the
  `.v2` topic convention later without code changes.

## R11. Test infrastructure

- **Decision**:
  - Unit: domain (pure JUnit 5 + AssertJ), application service (Mockito for every port).
  - Integration (`*IT`): persistence adapters and migrations against `postgres:16-alpine`
    Testcontainers, including the `CHECK` and unique constraints, the ordered lock query, and a
    concurrency test (20 threads reserving the same product through the real application service
    with a recording publisher).
  - Component (`*IT`): full context with PostgreSQL and `confluentinc/cp-kafka:7.6.1`
    (`ConfluentKafkaContainer`, same image as ecommerce-infra) via `@ServiceConnection`; events are
    produced with a test `KafkaProducer` and outcomes read with a test consumer; Awaitility for
    asynchronous assertions. Publish failure is simulated with `@MockitoSpyBean` on the publisher.
  - A shared abstract base class starts each container once per JVM (singleton containers) to keep
    `verify` fast.
- **Rationale**: Principles VI and VII; the image matches local infrastructure.

## R12. Agent context

- **Decision**: There is no `update-agent-context` script in this project. `CLAUDE.md` is updated
  manually as a task at the end of the feature (new packages, tables, topics, seed data, and the
  local profile).
