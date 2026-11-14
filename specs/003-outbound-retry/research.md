# Research: Retry Delivery of Inventory Events That Failed to Publish (US-03)

Phase 0 output for [plan.md](plan.md). Constitution references are to v1.0.1. The design follows the
same decisions as ecommerce-orders ADR 0005 (dead-letter retry) so both services behave alike.

## R1. Claiming rows: one row per transaction, FOR UPDATE SKIP LOCKED, no lease

- **Decision**: A round processes up to `batch-size` rows, **one transaction per row**:
  `select … from outbound_dead_letters where status = 'PENDING' and next_retry_at <= now()
  order by next_retry_at limit 1 for update skip locked`, publish the stored message while the row
  lock is held, write the outcome (resolved, failed attempt, exhausted), commit. Then claim the next
  row. There is no lease column: if the instance dies mid-row, the transaction rolls back and the row
  is due again at once.
- **Rationale**: SKIP LOCKED gives FR-003 directly: another instance skips locked rows without
  waiting. One row per transaction bounds how long a lock and a connection are held to one publish
  (the publish timeout), and makes every row change atomic (FR-011). A crash can only cause a second
  publish of the same event id (FR-007), never a lost or half-updated row (SC-006).
- **Alternatives considered**:
  - Claim the whole batch in one transaction and publish inside it: the transaction would last up to
    `batch-size × publish timeout` (50 × 15 s) while holding 50 locks and a connection.
  - Claim a batch by setting a "claimed until" lease, commit, publish outside the transaction: needs a
    lease that outlasts the worst-case batch time and recovery for expired leases; more moving parts
    for no gain at this scale (rejected in Orders for the same reason).

## R2. Due-ness uses the database clock

- **Decision**: "Due" is `next_retry_at <= now()` evaluated by PostgreSQL. Times written by the worker
  (`next_retry_at`, `resolved_at`, `updated_at`) come from the injected `Clock`.
- **Rationale**: All instances agree on which rows are due regardless of small JVM clock skew (spec
  edge case). Writing with the injected clock keeps the backoff unit-testable.

## R3. Fail fast within a round

- **Decision**: If an attempt fails, the round records that failure and **stops**; the remaining due
  rows stay untouched and are taken by the next round.
- **Rationale**: When the broker is down, every publish waits up to the producer's `max.block.ms`
  (10 s). Continuing would spend up to `batch-size × 10 s` per round on certain failures and grow
  every row's attempt count in lockstep. Stopping keeps a round short, keeps shutdown quick (FR-015)
  and still backs off the row that failed. A row that fails for a reason of its own (for example a
  payload the broker rejects) only delays the others by one round, because its own `next_retry_at`
  moves into the future.
- **Alternatives considered**: continue through the batch (slow, noisy during outages); a circuit
  breaker (extra state and dependency for the same effect).

## R4. Scheduling, no overlap, graceful shutdown

- **Decision**: `OutboundRetryScheduler` (infrastructure) uses Spring's `@Scheduled(fixedDelayString =
  "${inventory.outbound-retry.interval}")` with `@EnableScheduling`, guarded by
  `inventory.outbound-retry.enabled` (default `true`). `fixedDelay` starts the next round only after the
  previous one ended, so rounds never overlap on one instance (FR-004). The scheduler calls
  `OutboundRetryService.retryDueBatch()`, which catches and logs any unexpected exception so the next
  round still runs (FR-013). The scheduler runs on Spring's task-scheduling thread, never on a Kafka
  listener thread (FR-013).
  Shutdown: `OutboundRetryScheduler` is a `SmartLifecycle` with the highest phase, so Spring stops it
  **before** destroying the data source and the Kafka producer. Its `stop()` stops new rounds and calls
  `OutboundRetryService.stop()`, which sets a `stopping` flag (checked before claiming each row) and
  waits, up to publish timeout + 5 s, for the round in progress to return. The row being sent therefore
  commits as resolved or as a failed attempt, or rolls back unchanged; unclaimed rows are untouched
  (FR-015). This does not rely on `spring.task.scheduling.shutdown.*`, which configures the thread-pool
  scheduler: with `spring.threads.virtual.enabled=true` Spring Boot uses `SimpleAsyncTaskScheduler`
  instead, so those properties may have no effect (verified by `OutboundRetryShutdownIT`).
- **Rationale**: built into Spring Boot, no new dependency; `fixedDelay` is the documented way to avoid
  overlap.
- **Alternatives considered**: a self-rescheduling task (what Orders does in Node; in Spring
  `fixedDelay` is the idiomatic equivalent); ShedLock (cross-instance scheduling lock, unnecessary
  because SKIP LOCKED already makes concurrent rounds safe).

## R5. Pure backoff policy

- **Decision**: `OutboundRetryPolicy` (application, record): `maxAttempts`, `baseDelay`, `multiplier`,
  `maxDelay`; `Duration delayAfter(int attempt)` = `min(baseDelay × multiplier^(attempt−1), maxDelay)`
  computed in doubles and capped before converting (no overflow); `boolean isExhausted(int attempt)` =
  `attempt >= maxAttempts`. Validated at construction (`maxAttempts ≥ 1`, delays > 0, `multiplier ≥ 1`,
  `maxDelay ≥ baseDelay`); the bean is built from `InventoryProperties` so invalid settings stop startup
  (FR-016).
- **Rationale**: Deterministic, unit-testable with no clock or Spring (FR-009, SC-002). No jitter: rows
  were created at different times, so their schedules are already spread; jitter would make the
  schedule harder to verify.

## R6. Publishing: the raw publisher, not OutcomePublisher

- **Decision**: The worker publishes through the existing `OutboundMessagePublisher` port
  (`KafkaOutboundMessagePublisher`: synchronous send, bounded by `publish-timeout`), with the stored
  `OutboundMessage` (event id, topic, key, payload) unchanged (FR-005). It does **not** use
  `OutcomePublisher`, whose job is to dead-letter a failed first publish; the worker records the failure
  on the row it already holds.
- **Rationale**: Reuses the single Kafka publishing path and keeps one dead-letter mechanism. CLAUDE.md
  will state the rule precisely: request flows publish through `OutcomePublisher`; only the retry worker
  uses the raw publisher.

## R7. Re-failure of an event that already has a row (FR-012)

- **Decision**: Change the upsert in `OutboundDeadLetterRepositoryAdapter.recordFailure`:
  `on conflict (event_id) do update set last_error = excluded.last_error, updated_at = excluded.updated_at,
  status = case when status = 'RESOLVED' then 'PENDING' else status end,
  retry_count = case when status = 'RESOLVED' then 0 else retry_count end,
  next_retry_at = case when status = 'RESOLVED' then excluded.next_retry_at else next_retry_at end,
  resolved_at = case when status = 'RESOLVED' then null else resolved_at end`.
- **Rationale**: A Resolved row would otherwise swallow a new failure for ever. Pending rows keep their
  schedule (the worker already owns them); Exhausted rows stay for manual inspection (spec edge cases).
- **Accepted interaction**: if the worker holds the row lock while publishing (at most one publish
  timeout) and a redelivered message fails to republish the same event at that moment, the upsert waits
  for the lock on the listener thread. It is rare and bounded, so it is accepted and recorded in ADR 0006
  and the spec's edge cases rather than adding a lock timeout and a second failure path.

## R8. Schema change

- **Decision**: `V4__outbound_retry.sql`: add `outbound_dead_letters.resolved_at timestamptz null`,
  backfill `resolved_at = updated_at` for any existing `RESOLVED` row (none expected), then add the
  check `(status = 'RESOLVED') = (resolved_at is not null)`. The existing index
  `ix_outbound_dead_letters_due (status, next_retry_at)` serves the claim query.
- **Rationale**: The only missing column (spec Key Entities); everything else exists.

## R9. Configuration

- **Decision**: New `InventoryProperties.OutboundRetry` record bound from `inventory.outbound-retry.*`:
  `enabled` (`OUTBOUND_RETRY_ENABLED`, true), `interval` (`OUTBOUND_RETRY_INTERVAL`, 30s), `batch-size`
  (`OUTBOUND_RETRY_BATCH_SIZE`, 50), `max-attempts` (`OUTBOUND_RETRY_MAX_ATTEMPTS`, 10), `base-delay`
  (`OUTBOUND_RETRY_BASE_DELAY`, 30s), `multiplier` (`OUTBOUND_RETRY_MULTIPLIER`, 2.0), `max-delay`
  (`OUTBOUND_RETRY_MAX_DELAY`, 1h), all Bean-Validated. The existing
  `inventory.outbound-dead-letter.first-retry-delay` (30 s) keeps deciding when the first worker attempt
  is due.
- **Rationale**: FR-016; same binding and validation approach as US-01/US-02.

## R10. Logging

- **Decision**: Per round with at least one row: `INFO Outbound retry round: claimed={} resolved={}
  failed={} exhausted={}`. Per failed attempt: `WARN Outbound retry of {id} to {topic} failed (attempt
  {n}/{max}), next attempt at {time}: {error}`. Exhausted: `ERROR Outbound dead letter {id} exhausted
  after {n} attempts: event {eventId}, topic {topic}, key {key}, last error {error}`. Idle rounds: DEBUG
  only.
- **Rationale**: FR-010, FR-017, SC-007.

## R11. Tests

- **Decision**:
  - Unit: `OutboundRetryPolicy` (delays, cap, exhaustion, validation); `OutboundRetryService`
    (resolved, failed with backoff, exhausted, fail-fast stop, stop flag, exception in a round is
    contained, idle round).
  - Integration (real PostgreSQL): V4 migration and check; adapter claim (due only, oldest first,
    SKIP LOCKED: a second transaction skips a row locked by the first), mark resolved / failed /
    exhausted; the FR-012 upsert transitions; **multi-worker IT**: 3 concurrent workers, 50 due rows,
    recording publisher that is slow enough to overlap → each row published exactly once (SC-003).
  - Component (real Kafka): a due Pending row is delivered by the running scheduler with identical key,
    event id and payload, then Resolved; a failing publish (publisher spy) backs off and records the
    error; reaching max attempts → Exhausted. The component base enables the worker with a short
    interval; other contexts disable it (`inventory.outbound-retry.enabled=false`) so integration tests
    stay deterministic.
