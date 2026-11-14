# 0006. Outbound retry worker: one row per transaction with FOR UPDATE SKIP LOCKED

- **Status**: Accepted
- **Date**: 2026-11-14

## Context

US-01 and US-02 publish an outcome (`inventory.stock-reserved`, `-rejected`, `-released`) after the
database commit. When that publish fails, the message is stored in `outbound_dead_letters` as `PENDING`
(ADR 0004). Nothing redelivered those rows, so an outcome could be lost for good while Kafka was down.

The redelivery must: run in the background without blocking or crashing the service; never send one row
from two instances at once; keep the stored `eventId` so consumers can deduplicate; back off while the
broker stays down and give up after a bounded number of attempts; stop cleanly on shutdown.

## Decision

1. **One row per transaction.** A round repeats, up to `batch-size` times: open a transaction, claim the
   oldest due `PENDING` row with `SELECT … FOR UPDATE SKIP LOCKED LIMIT 1`, publish its stored topic, key
   and payload unchanged, record the result (`RESOLVED` + `resolved_at`, a failed attempt with the next
   retry time, or `EXHAUSTED`), commit. The row lock is the claim: other instances skip it instead of
   waiting, and a crash releases it. No lease or "in progress" status is needed. A transaction is held
   for at most one publish (bounded by the publish timeout).
2. **Fail fast.** The first failed attempt ends the round: the broker is probably down, and trying the
   rest of the batch would only multiply timeouts.
3. **Fixed-delay schedule.** `@Scheduled(fixedDelay = interval)`: the next round starts one interval
   after the previous one ended, so rounds never overlap; a lock in the service also makes overlapping
   calls a no-op. The first round runs one interval after startup, so startup never waits for Kafka.
4. **Pure backoff policy.** `OutboundRetryPolicy` (application layer, no Spring): delay after attempt
   `n` is `min(base-delay × multiplier^(n−1), max-delay)`; after `max-attempts` the row is `EXHAUSTED`,
   logged at error level and kept for manual inspection. No jitter: rows fail at different moments, so
   their schedules are already spread. Invalid settings stop the service at startup.
5. **Raw publisher.** The worker calls `OutboundMessagePublisher` directly. `OutcomePublisher` would
   dead-letter a failure a second time.
6. **Re-failure upsert.** When a request flow fails to publish an event that already has a row (a
   redelivered order event republishes its stored outcome): a `PENDING` or `EXHAUSTED` row only gets the
   new error; a `RESOLVED` row is reopened as `PENDING` with retry count 0, the new next retry time and no
   `resolved_at`.
7. **Shutdown.** The scheduler is a `SmartLifecycle` in the highest phase, so it stops first, before the
   data source and the Kafka producer close. It stops scheduling, and the service stops claiming and
   waits up to `publish-timeout + 5 s` for the row in progress. Rows not yet claimed stay as they were.
   This does not rely on `spring.task.scheduling.shutdown.*`, which may not apply to the
   `SimpleAsyncTaskScheduler` used with virtual threads.

Rejected alternatives: claiming a whole batch in one transaction (holds many locks for many publish
timeouts); an `IN_PROGRESS` status with a lease (needs a reaper for crashed instances); a Kafka
transactional outbox or Debezium (a second mechanism and new infrastructure for the same table).

## Consequences

- Outcomes that failed to publish are delivered once the broker is back, at least once and with the
  same `eventId`. Consumers must stay idempotent by `eventId` (already required).
- Several instances can run the worker; `OutboundRetryConcurrencyIT` proves each row is published once.
- **Accepted rare wait**: if a redelivered order event fails to publish while the worker holds that same
  row's lock, the request flow's dead-letter upsert waits for the worker's transaction (at most one
  publish timeout). It is rare (same event, same moment, broker failing) and bounded.
- Exhausted rows need manual recovery (set them back to `PENDING`, see README). Alerting is out of scope.
- Every test context that runs the worker uses its own database, because Spring caches contexts and a
  live worker would process other tests' rows.
