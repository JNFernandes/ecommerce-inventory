# Implementation Plan: Release Reserved Stock When an Order Is Cancelled (US-02)

**Branch**: `feature/US-02-release-stock` | **Date**: 2026-11-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-release-stock/spec.md`

## Summary

Inventory consumes `orders.order-cancelled`. Inside one transaction that first takes a per-order
advisory lock, it checks idempotency, finds the order's reservation, and lets
`Reservation.release` decide: a `RESERVED` reservation gives every reserved quantity back (stock rows
locked in productId order) and becomes `RELEASED`; `REJECTED` and `RELEASED` reservations are ignored.
When no reservation exists, a cancellation marker is stored, and the US-01 reserve handler (which now
takes the same per-order lock) skips any later placement of that order. The release, the outcome
message and the processed event are saved together; `inventory.stock-released` is published after
the commit through a shared `OutcomePublisher` that dead-letters failures exactly as US-01. Design
decisions: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (`-Xlint:all -Werror`)

**Primary Dependencies**: Spring Boot 3.5.16 (spring-kafka, Spring Data JPA, Flyway, Bean Validation,
Jackson). No new dependency.

**Storage**: PostgreSQL 16; migration `V3__release_stock.sql` (reservations `RELEASED` + `released_at`,
`cancellation_markers`, `processed_events` changes, `outcome_messages` no longer unique per
reservation)

**Testing**: JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL, Kafka), Awaitility, JaCoCo 80%

**Target Platform**: Linux container; local run on Windows via `mvnw.cmd`

**Project Type**: Event-driven backend microservice (Kafka in, Kafka out)

**Performance Goals**: stock-released published within 2 s of the cancellation (SC-001)

**Constraints**: a cancelled order never ends `RESERVED` whatever the timing (FR-012); stock released at
most once per reservation; DB commit → publish → offset; reuse the US-01 retry and dead-letter
mechanisms (FR-020); lock order: order lock, then stock rows by productId

**Scale/Scope**: one new consumed topic, one new produced topic, one new table, changes to three US-01
tables; about 20 new or changed production classes

No `NEEDS CLARIFICATION` remains (research R1–R10).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | How this plan complies | Pre | Post |
|---|---|---|---|---|
| I | DDD & layers | `OrderCancelledListener` parses and calls `ReleaseStockService` only. Release rules (which statuses release, the transition, all-or-nothing, missing stock) live in `Reservation.release` and `StockItem.release`. The marker check in `ReserveStockService` is a guard of the same kind as US-01's idempotency and duplicate-order checks. The advisory lock is behind the application port `OrderLock`; the domain has no Spring/JPA/Kafka. | ✅ | ✅ |
| II | No API | Nothing HTTP added. | ✅ | ✅ |
| III | Write flow | One transaction: order lock, stock, reservation status, marker, outcome message, processed event. Publish after commit via `OutcomePublisher`; ack after the service returns. Outbound dead letter in its own transaction (v1.0.1). | ✅ | ✅ |
| IV | Stock integrity | Release only from `RESERVED`, exactly once (status transition + idempotency); never-reserved and already-released ignored with a log line, as the principle states. Stock rows locked in productId order; CHECK constraint unchanged; release overflow checked. | ✅ | ✅ |
| V | Types & validation | Record DTO + Bean Validation + strict reader; enums for every outcome and status; `Optional` instead of nullable accessors in `ProcessedEvent`; new domain exception. | ✅ | ✅ |
| VI | Test coverage | Unit tests for every new domain and service method, happy and error paths. | ✅ | ✅ |
| VII | Testing strategy | Unit, integration (migration, adapters, advisory lock, place/cancel concurrency), component (full flow on real Kafka). | ✅ | ✅ |
| VIII | Kafka | New topics configured, key = reservation id, JSON per `events.md`, Javadoc on message fields, idempotency by `eventId`, same error handler and dead-letter tables, startup without Kafka preserved. `events.md` update delivered on its own infra branch. | ✅ | ✅ |
| IX | Branching | `feature/US-02-release-stock` from `main` (US-01 merged); Conventional Commits `feat(US-02): ...`. | ✅ | ✅ |
| X | Build & quality | Four-step gate after every task; new packages get `package-info.java`. | ✅ | ✅ |

**Result**: PASS before and after design. No violations.

**Post-design notes**:

- The race rule as specified (check marker / check reservation) is not safe under concurrency on its
  own; R1's per-order advisory lock is what makes FR-012 hold. Recorded in **ADR 0005**, which also
  resolves the open point of ADR 0003.
- US-01 code changes (no behaviour change for existing cases): `ReserveStockService` takes the order
  lock and checks markers; `publishOrDeadLetter` moves to `OutcomePublisher`; redelivery looks the
  message up by `processed_events.outcome_event_id` (V3 backfills existing rows); `ProcessedEvent`
  fields become `Optional`.

## Project Structure

### Documentation (this feature)

```text
specs/002-release-stock/
├── spec.md
├── plan.md               # this file
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/kafka-events.md
├── checklists/requirements.md
└── tasks.md              # /speckit-tasks
```

### Source Code (changes and additions)

```text
src/main/java/com/ecommerce/inventory/
├── domain/
│   ├── model/        ReservationStatus (+RELEASED), ReleaseOutcome, Reservation (+release,
│   │                 releasedAt, restoreReleased), StockItem (+release), CancellationMarker
│   ├── event/        StockReleased (+ permits)
│   ├── exception/    StockItemMissingException
│   └── repository/   ReservationRepository (+update), CancellationMarkerRepository
├── application/
│   ├── ReleaseStockService, ReleaseStockCommand, ReleaseStockOutcome
│   ├── OutcomePublisher                      (moved from ReserveStockService)
│   ├── ReserveStockService, ReserveStockOutcome   (order lock, marker check, +PlacementCancelled)
│   └── port/         OrderLock, ProcessedOutcome (+4), ProcessedEvent (Optional fields),
│                     OutcomeMessageRepository (findByEventId)
├── messaging/
│   ├── OrderCancelledListener, OrderCancelledMessageParser, StrictJsonParser (shared settings)
│   └── dto/          OrderCancelledMessage
└── infrastructure/
    ├── config/       InventoryProperties (+orderCancelled, stockReleased topics)
    ├── persistence/  PostgresOrderLock, CancellationMarkerEntity/Repository/Adapter,
    │                 ReservationEntity/Mapper/Adapter (+released_at, update),
    │                 ProcessedEventEntity/Adapter (+outcome_event_id), OutcomeMessage adapter
    └── kafka/        JsonOutboundMessageFactory (+StockReleased),
                      message/StockReleasedMessage, ReleasedItem

src/main/resources/
├── application.yml                     inventory.kafka.topics.order-cancelled / stock-released
└── db/migration/V3__release_stock.sql

src/test/java/com/ecommerce/inventory/
├── domain/model/        ReservationReleaseTest, StockItemTest (+release), CancellationMarkerTest
├── application/         ReleaseStockServiceTest, ReserveStockServiceTest (+marker, lock),
│                        OutcomePublisherTest, PlaceCancelRaceIT (concurrency)
├── messaging/           OrderCancelledMessageParserTest, OrderCancelledListenerTest
├── infrastructure/      ReleaseSchemaMigrationIT, CancellationMarkerRepositoryAdapterIT,
│                        PostgresOrderLockIT, ReservationRepositoryAdapterIT (+released)
├── component/           ReleaseStockComponentIT
└── support/             KafkaTestContainer (+2 topics), AbstractComponentIT (wait for both listeners)

docs/adr/0005-per-order-serialisation.md  (+ ADR 0003 open point marked resolved, index)
README.md, CLAUDE.md                       release flow, new tables, topics, env vars
```

**Structure Decision**: Same single Maven project and four layers as US-01; US-02 adds a parallel
listener → service → domain path and reuses every cross-cutting piece (error handler, dead letters,
outcome storage, idempotency).

## Delivery outside this repository

- `ecommerce-infra/docs/events.md`: the three edits in
  [contracts/kafka-events.md](contracts/kafka-events.md#changes-required-in-ecommerce-infradocseventsmd),
  on `chore/US-02-inventory-release-events` from `main` after the US-01 contract PR is merged.

## Complexity Tracking

No constitution violations to justify.
