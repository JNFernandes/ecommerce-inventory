# Data Model: Retry Delivery of Inventory Events That Failed to Publish (US-03)

Phase 1 output for [plan.md](plan.md). No domain (stock) model changes: the retry worker is an
application concern on top of the existing outbound dead-letter store.

## 1. Persistence (`V4__outbound_retry.sql`)

### `outbound_dead_letters` (existing, changed)

| Column | Type | Change |
|---|---|---|
| `id` | `uuid` | — |
| `event_id` | `uuid` | — (unique) |
| `topic`, `message_key`, `payload` | `varchar`, `varchar`, `jsonb` | — (republished unchanged) |
| `status` | `varchar(16)` | — values `PENDING`, `RESOLVED`, `EXHAUSTED` ("PendingRetry" = `PENDING`) |
| `retry_count` | `integer` | meaning confirmed: number of **worker** attempts so far |
| `next_retry_at` | `timestamptz` | — |
| `last_error` | `text` | — |
| `created_at`, `updated_at` | `timestamptz` | — |
| `resolved_at` | `timestamptz` | **new**, nullable; check `(status = 'RESOLVED') = (resolved_at is not null)` |

Index `ix_outbound_dead_letters_due (status, next_retry_at)` (existing) serves the claim query.

### State transitions

```text
                 recordFailure (first publish failed, US-01/US-02)
(none) ─────────────────────────────────────────────▶ PENDING (retry_count 0)

PENDING ──worker publish OK──────────────────────────▶ RESOLVED (resolved_at = now)
PENDING ──worker publish failed, attempt < max───────▶ PENDING (retry_count+1, next_retry_at = now + delay, last_error)
PENDING ──worker publish failed, attempt = max───────▶ EXHAUSTED (retry_count = max, last_error)

RESOLVED ──recordFailure again (FR-012)──────────────▶ PENDING (retry_count 0, next_retry_at = new, resolved_at null)
PENDING  ──recordFailure again───────────────────────▶ PENDING (only last_error, updated_at change)
EXHAUSTED ─recordFailure again───────────────────────▶ EXHAUSTED (only last_error, updated_at change)
```

## 2. Application (`com.ecommerce.inventory.application`)

| Type | Kind | Purpose |
|---|---|---|
| `OutboundRetryPolicy` | record | `batchSize`, `maxAttempts`, `baseDelay`, `multiplier`, `maxDelay`; `delayAfter(int attempt)`, `isExhausted(int attempt)`; validated in the constructor |
| `OutboundRetryService` | service | `RetryRoundResult retryDueBatch()`: up to `batchSize` times → in its own transaction claim the next due row, publish, record the outcome; stop at the first failure or when stopping; never throws |
| `RetryRoundResult` | record | `claimed`, `resolved`, `failed`, `exhausted` counts |
| `port/OutboundDeadLetter` | record, new | `UUID id`, `OutboundMessage message`, `int retryCount` (a claimed row) |
| `port/OutboundDeadLetterRepository` | port, extended | existing `recordFailure(...)` (now with FR-012 semantics) + `Optional<OutboundDeadLetter> claimNextDue()` (must run in a transaction; locks the row, skips locked rows), `markResolved(UUID id, Instant at)`, `recordFailedAttempt(UUID id, int retryCount, String error, Instant nextRetryAt)`, `markExhausted(UUID id, int retryCount, String error)` |

### Round algorithm (per instance, no overlap)

```text
claimed = resolved = failed = exhausted = 0
repeat up to batchSize times, unless stopping:
  in a new transaction:
    row = claimNextDue()                       -- PENDING and next_retry_at <= db now(), oldest first,
    if none: commit, stop the round            -- FOR UPDATE SKIP LOCKED, LIMIT 1
    claimed++
    try publish(row.message)                   -- same topic, key, payload, event id
      markResolved(row.id, now); resolved++
    on PublishFailedException e:
      attempt = row.retryCount + 1
      if policy.isExhausted(attempt): markExhausted(row.id, attempt, e); exhausted++; log ERROR
      else: recordFailedAttempt(row.id, attempt, e, now + policy.delayAfter(attempt)); failed++; log WARN
      commit, then stop the round (fail fast)
  commit
log INFO round summary if claimed > 0, DEBUG otherwise
```

## 3. Configuration (`InventoryProperties.OutboundRetry`)

| Property | Env var | Default | Validation |
|---|---|---|---|
| `inventory.outbound-retry.enabled` | `OUTBOUND_RETRY_ENABLED` | `true` | — |
| `inventory.outbound-retry.interval` | `OUTBOUND_RETRY_INTERVAL` | `30s` | > 0 |
| `inventory.outbound-retry.batch-size` | `OUTBOUND_RETRY_BATCH_SIZE` | `50` | ≥ 1 |
| `inventory.outbound-retry.max-attempts` | `OUTBOUND_RETRY_MAX_ATTEMPTS` | `10` | ≥ 1 |
| `inventory.outbound-retry.base-delay` | `OUTBOUND_RETRY_BASE_DELAY` | `30s` | > 0 |
| `inventory.outbound-retry.multiplier` | `OUTBOUND_RETRY_MULTIPLIER` | `2.0` | ≥ 1.0 |
| `inventory.outbound-retry.max-delay` | `OUTBOUND_RETRY_MAX_DELAY` | `1h` | ≥ base delay |

Existing and unchanged: `inventory.outbound-dead-letter.first-retry-delay` (when the first worker
attempt is due), `inventory.kafka.publish-timeout` (bounds one attempt).
