# Quickstart: Validate US-03 Outbound Retry

Tables and settings: [data-model.md](data-model.md). Prerequisites and helpers are the same as US-01
and US-02 ([001 quickstart](../001-reserve-stock/quickstart.md)).

## 1. Automated validation

```powershell
.\mvnw.cmd -B -q compile; .\mvnw.cmd -B -q checkstyle:check; .\mvnw.cmd -B -q spotless:check; .\mvnw.cmd -B verify
```

| Spec reference | Test |
|---|---|
| Story 1, FR-005, FR-006, SC-004 | component: a due row is delivered by the running worker with the same key, event id and payload; Resolved with `resolved_at` |
| Story 2, FR-008 to FR-010, SC-002 | unit: policy delays and cap; unit + component: failed attempt backs off, max attempts → Exhausted and an ERROR log |
| Story 3, FR-003, SC-003 | integration: 3 concurrent workers over 50 due rows publish each row exactly once |
| Story 4, FR-013, FR-015 | unit: stop flag, exception in a round contained, fail fast |
| FR-012 | integration: re-failure of a Resolved / Pending / Exhausted event |
| FR-016 | unit/integration: invalid settings refuse to start |

## 2. Manual scenarios against ecommerce-infra

Start the app (`.\mvnw.cmd spring-boot:run`). To shorten waits locally, run it with
`$env:OUTBOUND_RETRY_INTERVAL='5s'; $env:OUTBOUND_RETRY_BASE_DELAY='5s'; $env:OUTBOUND_RETRY_MAX_ATTEMPTS='3'`.

| # | Steps | Expected |
|---|---|---|
| A | Insert a `PENDING` row due now with a valid stored payload (or stop Kafka, place an order, start Kafka again) | within one interval the message appears on its topic with the stored key and `eventId`; row `RESOLVED`, `resolved_at` set; log `Outbound retry round: claimed=1 resolved=1 …` |
| B | Stop Kafka (`docker stop ecommerce-kafka-1`), let a due row be attempted | `retry_count` grows by one per attempt, `next_retry_at` moves out by the backoff, `last_error` set; WARN line per attempt; `/actuator/health` stays `UP` |
| C | Keep Kafka stopped until max attempts | row `EXHAUSTED`, ERROR log line with id, event id, topic, key, attempts, last error; no further attempts |
| D | Start Kafka again | Pending rows are delivered; the Exhausted row stays Exhausted |
| E | Set an Exhausted row back to `PENDING` with `next_retry_at = now()` (manual recovery) | delivered and Resolved on the next round |
