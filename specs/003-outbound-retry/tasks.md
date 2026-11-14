---

description: "Task list for US-03 Retry Delivery of Inventory Events That Failed to Publish"
---

# Tasks: Retry Delivery of Inventory Events That Failed to Publish (US-03)

**Input**: Design documents from `specs/003-outbound-retry/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/kafka-events.md](contracts/kafka-events.md),
[quickstart.md](quickstart.md)

**Tests**: REQUIRED by constitution Principles VI and VII (unit tests for every application method with
a happy and an error/edge case; integration tests on real PostgreSQL; component tests on real
PostgreSQL + Kafka). Within each story write the tests first.

**Organization**: by the spec's user stories (Story 1–4 → `[US1]`–`[US4]`). These labels are the stories
inside this spec, not backlog ids (`US-03` is this whole feature).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- Paths: `M/` = `src/main/java/com/ecommerce/inventory/`, `T/` = `src/test/java/com/ecommerce/inventory/`,
  `R/` = `src/main/resources/`

## Rules that apply to every task

- **Quality gate after every task** with JDK 21 on `JAVA_HOME`: `.\mvnw.cmd -B -q compile`,
  `.\mvnw.cmd -B -q checkstyle:check`, `.\mvnw.cmd -B -q spotless:check` (fix with
  `.\mvnw.cmd -B -q spotless:apply`), `.\mvnw.cmd -B verify`. Never commit a failing gate.
- Javadoc on every public type, method and record component; `package-info.java` for new packages.
- The stock domain is not touched. No new retry, dead-letter or publishing mechanism: reuse
  `outbound_dead_letters` and `OutboundMessagePublisher` (FR-005, request "do not create a second
  mechanism").
- The worker publishes through `OutboundMessagePublisher` directly, never through `OutcomePublisher`
  (which would dead-letter the failure again; research R6).
- Component tests: every spy, mock and property override stays in `T/support/AbstractComponentIT`.
- Conventional Commits with scope `US-03`.

---

## Phase 1: Setup

- [X] T001 Configuration: add the record `OutboundRetry(boolean enabled, @NotNull Duration interval, @Min(1) int batchSize, @Min(1) int maxAttempts, @NotNull Duration baseDelay, @DecimalMin("1.0") double multiplier, @NotNull Duration maxDelay)` (Javadoc `@param` each) as a new component `@Valid @NotNull OutboundRetry outboundRetry` of `InventoryProperties` in `M/infrastructure/config/InventoryProperties.java`; in `R/application.yml` add `inventory.outbound-retry` with `enabled: ${OUTBOUND_RETRY_ENABLED:true}`, `interval: ${OUTBOUND_RETRY_INTERVAL:30s}`, `batch-size: ${OUTBOUND_RETRY_BATCH_SIZE:50}`, `max-attempts: ${OUTBOUND_RETRY_MAX_ATTEMPTS:10}`, `base-delay: ${OUTBOUND_RETRY_BASE_DELAY:30s}`, `multiplier: ${OUTBOUND_RETRY_MULTIPLIER:2.0}`, `max-delay: ${OUTBOUND_RETRY_MAX_DELAY:1h}`, (graceful shutdown is handled by the worker itself in T019, not by `spring.task.scheduling.shutdown.*`); update the helper `KafkaOutboundMessagePublisherTest.properties(...)` in `T/infrastructure/kafka/KafkaOutboundMessagePublisherTest.java` for the new constructor argument
- [X] T002 Test infrastructure: disable the worker where tests must stay deterministic: add `registry.add("inventory.outbound-retry.enabled", () -> "false")` to the `@DynamicPropertySource` of `T/support/AbstractPostgresIT.java` and `inventory.outbound-retry.enabled=false` to the properties of `T/ApplicationHealthIT.java`; in `T/support/AbstractComponentIT.java` add the properties `inventory.outbound-retry.interval=200ms`, `inventory.outbound-retry.base-delay=200ms`, `inventory.outbound-retry.max-delay=1s`, `inventory.outbound-retry.max-attempts=3` (worker enabled, fast schedule) and a `protected void givenDueOutboundDeadLetter(UUID eventId, String topic, String key, String payload)` helper that inserts a `PENDING` row with `next_retry_at = now() - interval '1 second'`

**Checkpoint**: gate green; no behaviour change (the worker does not exist yet).

---

## Phase 2: Foundational

**⚠️ No story work before this phase is done.**

- [X] T003 Create `R/db/migration/V4__outbound_retry.sql`: `alter table outbound_dead_letters add column resolved_at timestamptz;` then `update outbound_dead_letters set resolved_at = updated_at where status = 'RESOLVED';` then `add constraint ck_outbound_dead_letters_resolved_at_iff_resolved check ((status = 'RESOLVED') = (resolved_at is not null))` (comment header referencing `specs/003-outbound-retry/data-model.md`)
- [X] T004 [P] Write `T/infrastructure/persistence/OutboundRetryMigrationIT.java` (extends `AbstractPostgresIT`): should refuse `RESOLVED` without `resolved_at` and `PENDING` with it; should accept `RESOLVED` with `resolved_at`
- [X] T005 [P] Create `M/application/OutboundRetryPolicy.java` (record `int batchSize, int maxAttempts, Duration baseDelay, double multiplier, Duration maxDelay`; compact constructor validates `batchSize >= 1`, `maxAttempts >= 1`, positive delays, `multiplier >= 1.0`, `maxDelay >= baseDelay`, else `IllegalArgumentException`; `Duration delayAfter(int attempt)` = `min(baseDelay × multiplier^(attempt−1), maxDelay)` computed in double millis and capped before converting; `boolean isExhausted(int attempt)` = `attempt >= maxAttempts`) and `T/application/OutboundRetryPolicyTest.java` (30 s, 1 min, 2 min, 4 min, 8 min then 15 min cap for base 30 s, ×2, max 15 min; huge attempt numbers stay at the cap without overflow; exhaustion exactly at `maxAttempts`; each invalid setting refused)
- [X] T006 Add the policy bean `@Bean OutboundRetryPolicy outboundRetryPolicy(InventoryProperties)` to `M/infrastructure/config/ApplicationPolicyConfiguration.java` (invalid settings therefore stop startup, FR-016)
- [X] T007 Extend the port: new record `M/application/port/OutboundDeadLetter.java` (`UUID id, OutboundMessage message, int retryCount`); in `M/application/port/OutboundDeadLetterRepository.java` add `Optional<OutboundDeadLetter> claimNextDue()` (Javadoc: must run inside a transaction; locks the oldest due `PENDING` row until the transaction ends; skips rows locked by others), `void markResolved(UUID id, Instant resolvedAt)`, `void recordFailedAttempt(UUID id, int retryCount, String error, Instant nextRetryAt)`, `void markExhausted(UUID id, int retryCount, String error)`; implement them in `M/infrastructure/persistence/OutboundDeadLetterRepositoryAdapter.java` with `JdbcTemplate` (`claimNextDue`: `select … where status = 'PENDING' and next_retry_at <= now() order by next_retry_at limit 1 for update skip locked`, annotated `@Transactional(propagation = MANDATORY)`; the three updates set `updated_at` from the `Clock` and `MANDATORY` too)
- [X] T008 FR-012 upsert: change `recordFailure` in `M/infrastructure/persistence/OutboundDeadLetterRepositoryAdapter.java` to `on conflict (event_id) do update set last_error = excluded.last_error, updated_at = excluded.updated_at, status = case when outbound_dead_letters.status = 'RESOLVED' then 'PENDING' else outbound_dead_letters.status end, retry_count = case … 0 …, next_retry_at = case … excluded.next_retry_at …, resolved_at = case … null …` per research R7; update the port Javadoc
- [X] T009 Extend `T/infrastructure/persistence/DeadLetterAdaptersIT.java`: `claimNextDue` returns only due `PENDING` rows, oldest `next_retry_at` first, and empty when none is due; a row claimed (and locked) in one open transaction is skipped by `claimNextDue` in a second transaction, which returns the next due row instead of waiting; `markResolved` sets `RESOLVED` and `resolved_at`; `recordFailedAttempt` sets `retry_count`, `last_error`, `next_retry_at`, keeps `PENDING`; `markExhausted` sets `EXHAUSTED`; claiming outside a transaction fails; FR-012: re-failure of a `RESOLVED` row → `PENDING`, `retry_count` 0, new `next_retry_at`, `resolved_at` null; of a `PENDING` row → only `last_error` changes; of an `EXHAUSTED` row → stays `EXHAUSTED` with the new error

**Checkpoint**: gate green; schema, policy and adapter ready, no worker yet.

---

## Phase 3: User Story 1 — Deliver outcomes that failed to publish (P1) 🎯 MVP

**Goal**: due Pending entries are republished unchanged and marked Resolved.

**Independent Test**: a due Pending entry with the broker reachable is on its topic within one
interval with the same key, event id and payload, and the entry is Resolved with `resolved_at`.

- [X] T010 [P] [US1] Write `T/application/OutboundRetryServiceTest.java` (Mockito: repository, `OutboundMessagePublisher`; journaling transaction stub from `T/application/JournalingTransactions.java`; fixed `Clock`): should claim, publish the stored message unchanged and mark it resolved, each row in its own transaction; should process up to `batchSize` rows and stop when none is due; should return `RetryRoundResult(claimed, resolved, failed, exhausted)`; an idle round claims nothing and publishes nothing
- [X] T011 [US1] Create `M/application/RetryRoundResult.java` (record of four counts) and `M/application/OutboundRetryService.java` (`@Service`; constructor: `OutboundDeadLetterRepository`, `OutboundMessagePublisher`, `OutboundRetryPolicy`, `TransactionOperations`, `Clock`): `RetryRoundResult retryDueBatch()` loops up to `policy.batchSize()` times, each iteration in `transactions.execute(...)`: `claimNextDue()` → empty ends the round → `publisher.publish(row.message())` → `markResolved(row.id(), clock.instant())`; log `INFO Outbound retry round: claimed={} resolved={} failed={} exhausted={}` when `claimed > 0`, DEBUG otherwise. Failure handling comes in T015
- [X] T012 [US1] Create `M/infrastructure/scheduling/OutboundRetryScheduler.java` (+ `package-info.java`): `@Component @ConditionalOnProperty(name = "inventory.outbound-retry.enabled", havingValue = "true", matchIfMissing = true)`, `@Scheduled(fixedDelayString = "${inventory.outbound-retry.interval}", initialDelayString = "${inventory.outbound-retry.interval}")` method calling `service.retryDueBatch()`; add `@EnableScheduling` on a configuration class in the same package (`SchedulingConfiguration`)
- [X] T013 [US1] Write `T/component/OutboundRetryComponentIT.java` (extends `AbstractComponentIT`): a due `PENDING` row for each of `inventory.stock-reserved`, `inventory.stock-rejected` and `inventory.stock-released` is delivered by the running scheduler (await up to 5 s) with exactly the stored key and payload (same `eventId`), then `RESOLVED` with `resolved_at`; a `PENDING` row due in one hour is not published and stays unchanged; a `RESOLVED` and an `EXHAUSTED` row are never published

**Checkpoint**: Story 1 works end to end; gate green. MVP.

---

## Phase 4: User Story 2 — Back off and give up gracefully (P1)

**Goal**: failed attempts back off exponentially; after `maxAttempts` the entry is Exhausted and logged
at error level; the service stays healthy.

**Independent Test**: with publishing failing, the attempt count grows by one per attempt with the
backoff schedule, the error is stored, and after the maximum attempts the entry is Exhausted.

- [X] T014 [P] [US2] Extend `T/application/OutboundRetryServiceTest.java`: on `PublishFailedException` → `recordFailedAttempt(id, retryCount + 1, error, now + policy.delayAfter(retryCount + 1))`, the round stops (the next due row is not claimed, fail fast) and the result counts one failed; when `retryCount + 1 == maxAttempts` → `markExhausted(id, maxAttempts, error)` and counts one exhausted; an unexpected `RuntimeException` from the repository is logged and `retryDueBatch()` returns normally (FR-013); log lines asserted with Spring Boot's `OutputCaptureExtension`: one WARN per failure with id, topic, attempt n/max and next attempt time; one ERROR with id, event id, topic, key, attempts and last error on exhaustion; the INFO round summary
- [X] T015 [US2] Implement failure handling in `M/application/OutboundRetryService.java`: catch `PublishFailedException` around the publish (inside the row transaction), compute `attempt = retryCount + 1`, then `markExhausted` (ERROR log) or `recordFailedAttempt` with `clock.instant().plus(policy.delayAfter(attempt))` (WARN log), commit, and stop the round; wrap the whole round in `try { … } catch (RuntimeException e) { LOG.error(…) }` so a round never throws; error text = exception and causes, as in `OutcomePublisher`
- [X] T016 [US2] Add to `T/component/OutboundRetryComponentIT.java`: with the publisher spy throwing `PublishFailedException` for the row's event id → `retry_count` grows to 1, 2 with `next_retry_at` moving out (200 ms, 400 ms under the component settings) and `last_error` set, then `EXHAUSTED` after 3 attempts and no further publish attempts for 2 s; after the spy stops throwing and the row is set back to `PENDING` due now (manual recovery), it is delivered and `RESOLVED`. **M1 (FR-013, SC-005)**: while the worker keeps failing on that row, place an order for an in-stock product and assert its `inventory.stock-reserved` message arrives within 2 s (the failing worker does not slow order handling). **M4 (FR-004)**: add `@MockitoSpyBean OutboundRetryService` to `T/support/AbstractComponentIT.java` and, with the publisher spy sleeping 500 ms per publish (longer than the 200 ms interval) for a few due rows, record the number of concurrent `retryDueBatch()` invocations with a `doAnswer` counter and assert it never exceeds 1
- [X] T017 [US2] Add to `T/ApplicationHealthIT.java` (or a new `T/OutboundRetryWithoutKafkaIT.java` with `inventory.outbound-retry.enabled=true`, interval 200 ms, Kafka on `localhost:1`): insert a due row, await `retry_count >= 1`, and assert `/actuator/health` is `UP` and the context is still running (FR-014, SC-005)

**Checkpoint**: Stories 1 and 2 work.

---

## Phase 5: User Story 3 — Run safely on several instances at once (P1)

**Goal**: concurrent workers never process the same row twice.

**Independent Test**: 3 workers, 50 due rows, one round each at the same moment → each row published
exactly once.

- [X] T018 [US3] Write `T/application/OutboundRetryConcurrencyIT.java` (extends `AbstractPostgresIT`, worker disabled, real `OutboundRetryService` and adapter, `@MockitoBean OutboundMessagePublisher` that records event ids in a concurrent list and sleeps 20 ms per publish so rounds overlap): insert 50 due rows; run `retryDueBatch()` from 3 threads released by one latch, repeated until no `PENDING` row is left; assert the recorded event ids are exactly the 50 distinct ids (no duplicates), all rows `RESOLVED`, no thread blocked longer than the lock timeout (SC-003); and a second test where one thread holds a row lock in an open transaction while another round runs and skips it (FR-003)

**Checkpoint**: Stories 1–3 work.

---

## Phase 6: User Story 4 — Stop cleanly, log and configure (P2)

**Goal**: graceful shutdown, documented settings, refused invalid settings.

- [X] T019 [US4] Graceful shutdown that does not depend on the task-scheduler settings (virtual threads are enabled, so Spring Boot uses `SimpleAsyncTaskScheduler` and `spring.task.scheduling.shutdown.*` may not apply; research R4): add `void stop()` and a `volatile boolean stopping` to `M/application/OutboundRetryService.java` (the batch loop checks it before claiming each row, and `stop()` waits, up to `publish-timeout + 5 s`, until the round in progress returns, using a lock or latch held for the duration of `retryDueBatch()`); make `M/infrastructure/scheduling/OutboundRetryScheduler.java` a `SmartLifecycle` with `getPhase()` = `Integer.MAX_VALUE` so it stops **first**, before beans (data source, Kafka producer) are destroyed: `stop()` stops scheduling new rounds and calls `service.stop()`; `isRunning()` reflects it. Tests: extend `T/application/OutboundRetryServiceTest.java` (after `stop()` no new row is claimed; a stop requested while a row is being published waits for that row to complete as resolved or failed attempt and claims nothing more; `stop()` returns within the bound when nothing runs) and write `T/application/OutboundRetryShutdownIT.java` (own Spring context with worker enabled, interval 100 ms, `@MockitoBean OutboundMessagePublisher` that blocks 2 s per publish): insert 5 due rows, await the first publish to start, close the context, then assert with plain JDBC on the still-running PostgreSQL container that the row being sent is `RESOLVED` (or a recorded failed attempt) and never half-updated, the other 4 rows are unchanged `PENDING` with `retry_count` 0, and the close took less than `publish-timeout + 5 s` (SC-006, FR-015)
- [X] T020 [P] [US4] Write `T/infrastructure/config/OutboundRetryPropertiesTest.java` with `ApplicationContextRunner` (`InventoryProperties` + `ApplicationPolicyConfiguration`, validation enabled): defaults bind to 30 s / 50 / 10 / 30 s / 2.0 / 1 h; environment-style overrides are applied; `max-attempts=0`, `batch-size=0`, `multiplier=0.5` and `max-delay` below `base-delay` each make the context fail to start (FR-016)

**Checkpoint**: all stories work.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T021 [P] Write `docs/adr/0006-outbound-retry-worker.md` (Context / Decision / Consequences: one row per transaction with `FOR UPDATE SKIP LOCKED`, no lease, fail-fast rounds, fixed-delay scheduling, pure backoff policy, raw publisher, FR-012 upsert, `SmartLifecycle` shutdown that stops first, and the accepted rare listener wait when a redelivery re-fails the entry the worker is sending; consistent with ecommerce-orders ADR 0005) and add it to `docs/adr/README.md`
- [ ] T022 [P] Update `README.md`: status (US-03 implemented), a "How failed publishes are retried" section, the seven `OUTBOUND_RETRY_*` settings with defaults in the configuration table, `resolved_at` in the tables section, and how to recover an Exhausted row manually (`update outbound_dead_letters set status = 'PENDING', next_retry_at = now(), retry_count = 0 where id = …`)
- [ ] T023 [P] Update `CLAUDE.md`: the retry worker (files, round algorithm, fail fast, no overlap), the refined publishing rule ("request flows publish through `OutcomePublisher`; only `OutboundRetryService` uses `OutboundMessagePublisher` directly"), the FR-012 upsert semantics, the test switch `inventory.outbound-retry.enabled`, ADR 0006
- [ ] T024 Review coverage in `target/site/jacoco/index.html` (every `domain` and `application` package ≥ 80%); add unit tests for any gap
- [ ] T025 Run the manual scenarios A–E of [quickstart.md](quickstart.md) against ecommerce-infra (including stopping and starting `ecommerce-kafka-1`) and record the results
- [ ] T026 Final clean gate: `.\mvnw.cmd -B clean verify`

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2 → stories → Polish.**
- **US1** depends on Phase 2. **US2** extends the US1 service. **US3** needs the US1/US2 service and
  the T007 claim. **US4** needs the service (T019) and the properties (T020 only needs T001 and T006).

Key task dependencies: T001 → T002, T006, T020; T003 → T004, T007, T008; T005 → T006 → T011; T007 →
T009, T011; T011 → T012 → T013; T011 → T015 → T016, T017; T015 → T018, T019.

## Parallel Examples

```text
# Phase 2 after T003:
T004 migration IT | T005 policy + test | T007 port/adapter (then T008, T009)

# Story 1: T010 service tests first, then T011 → T012 → T013
# Story 4: T020 properties test in parallel with T019
# Polish: T021 ADR | T022 README | T023 CLAUDE.md
```

## Implementation Strategy

1. **MVP**: Phases 1–3 (T001–T013): stored outcomes are delivered once the broker is back.
2. Then US2 (backoff, exhaustion, health without Kafka), US3 (multi-instance proof), US4 (shutdown,
   config validation).
3. Polish: ADR 0006, docs, coverage, manual scenarios with Kafka stopped and started, clean build.

All stories ship in one PR for `feature/US-03-outbound-retry`.
