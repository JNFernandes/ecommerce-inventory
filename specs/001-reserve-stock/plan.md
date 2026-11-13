# Implementation Plan: Reserve Stock When an Order Is Placed (US-01)

**Branch**: `feature/US-01-reserve-stock` | **Date**: 2026-11-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-reserve-stock/spec.md`

## Summary

Inventory consumes `orders.order-placed`, consolidates the order's items, locks the matching stock
rows in ascending `productId` order, and lets the `Reservation` aggregate decide all-or-nothing:
either every item is reserved (stock decreased) or the order is rejected with the failing items and
no stock change. The reservation, stock changes, the serialized outcome message and the
processed-event record are saved in one PostgreSQL transaction; the outcome is then published to
`inventory.stock-reserved` or `inventory.stock-rejected`, and only then is the offset acknowledged.
Redeliveries republish the stored outcome; a second order-placed for the same order is ignored.
Publish failures go to `outbound_dead_letters`; unprocessable inbound messages are retried with
exponential backoff and then stored in `consumed_dead_letters`. A `local` profile seeds five sample
products. Design decisions: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (`-Xlint:all -Werror`)

**Primary Dependencies**: Spring Boot 3.5.16 (spring-kafka, Spring Data JPA / Hibernate 6,
Flyway, Jakarta Bean Validation, Jackson). No new runtime dependency.

**Storage**: PostgreSQL 16 (`inventory` database); schema by Flyway `V2__reserve_stock_schema.sql`;
local-only seed in `db/local/R__seed_local_stock.sql`

**Testing**: JUnit 5, AssertJ, Mockito, Testcontainers (`postgres:16-alpine`,
`confluentinc/cp-kafka:7.6.1`), Awaitility, JaCoCo (80% line coverage on domain and application)

**Target Platform**: Linux container (eclipse-temurin 21 JRE), local run on Windows via `mvnw.cmd`

**Project Type**: Event-driven backend microservice (Kafka in, Kafka out; no business HTTP API)

**Performance Goals**: outcome published within 2 s of the order-placed message being available
(SC-001); 20 concurrent orders on one product handled without overselling (SC-002)

**Constraints**: never negative stock; one reservation per order; DB commit → publish → offset;
lock waits bounded (`lock_timeout` 5 s); inbound retries 3 with backoff 1 s → 10 s; publish
blocking bounded (`max.block.ms` 10 s, `delivery.timeout.ms` 15 s) to stay well within
`max.poll.interval.ms`

**Scale/Scope**: one consumed topic, two produced topics, 7 tables, about 40 production classes;
order sizes of tens of items

No `NEEDS CLARIFICATION` remains; all open questions are resolved in [research.md](research.md)
(R1–R12).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | How this plan complies | Pre | Post |
|---|---|---|---|---|
| I | DDD & layered architecture | Listener parses/validates and calls `ReserveStockService` only. The service orchestrates (idempotency → lock → `Reservation.decide` → save → publish). All rules (consolidation, all-or-nothing, failure kinds, reason) live in `domain`, which imports no Spring/JPA/Kafka. JPA entities stay in `infrastructure.persistence` with explicit mappers. The stored outcome message is behind an application port, so the domain has no messaging types (data-model §1–2). | ✅ | ✅ |
| II | Event-driven, no API | No controller or HTTP endpoint added; actuator health only. | ✅ | ✅ |
| III | Write flow integrity | One `TransactionOperations` block saves stock, reservation, outcome message and processed event; publish happens after the block returns; the listener acks after the service returns (R3). No publish inside a transaction. | ✅ | ✅ |
| IV | Stock integrity | Domain check + `CHECK (available_quantity >= 0)`; all-or-nothing in `Reservation.decide`; ordered `FOR UPDATE` per ADR 0002 with bounded `lock_timeout`; `UNIQUE (order_id)`; unknown product rejects the order. Release-once rules belong to US-02. | ✅ | ✅ |
| V | Type safety & validation | Immutable record DTO + Bean Validation, strict Jackson reader (no coercion), `version == 1`; enums for status, reason, failure kind, processed outcome; `Optional` returns; domain exceptions; no `Throwable` catch. | ✅ | ✅ |
| VI | Test coverage | Unit tests for every domain method and `ReserveStockService` path (happy + error/edge each). | ✅ | ✅ |
| VII | Testing strategy | Unit (domain, application), integration (persistence adapters, migrations, constraints, concurrency on real PostgreSQL), component (full context, real PostgreSQL + Kafka, events produced by the test). Awaitility; JaCoCo ≥ 80%. | ✅ | ✅ |
| VIII | Kafka events | Topics and payloads per `events.md` (contracts/); key = `aggregateId` = reservation id; JSON; Javadoc on every message field; idempotency by `eventId`; offset after DB save; `DefaultErrorHandler` exponential backoff → `consumed_dead_letters` + commit; publish failure → `outbound_dead_letters` (status, retry count, next retry, last error); startup without Kafka preserved (listener started by `KafkaListenerStarter`). `events.md` update delivered with the feature. | ✅ | ✅ |
| IX | Branching | Work on `feature/US-01-reserve-stock` from `main`; Conventional Commits `feat(US-01): ...`. | ✅ | ✅ |
| X | Build & quality | Four-step gate after every task. New sub-packages each get a `package-info.java` (Checkstyle `JavadocPackage`). | ✅ | ✅ |

**Result**: PASS before and after design. No violations, so Complexity Tracking is empty.

**Design notes from the post-design re-check**:

- The first data-model draft had the domain `ReservationRepository` store an application-layer
  `OutboundMessage`, which breaks Principle I. It was changed to a separate `outcome_messages`
  table behind the application port `OutcomeMessageRepository`.
- ADR 0002 already covers the locking strategy. A new **ADR 0004** records the
  "save outcome with the reservation, publish after commit, republish on redelivery" decision (R3,
  R4), because it shapes US-02 and US-03 too.

## Project Structure

### Documentation (this feature)

```text
specs/001-reserve-stock/
├── spec.md
├── plan.md               # this file
├── research.md           # Phase 0
├── data-model.md         # Phase 1
├── quickstart.md         # Phase 1
├── contracts/
│   └── kafka-events.md   # Phase 1
├── checklists/
│   └── requirements.md
└── tasks.md              # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

New packages each get a `package-info.java`. Existing files are marked `(existing)`.

```text
src/main/java/com/ecommerce/inventory/
├── domain/
│   ├── model/            ProductId, OrderId, ReservationId, Quantity, RequestedItem,
│   │                     RequestedItems, ItemFailure, FailureKind, RejectionReason,
│   │                     ReservationStatus, Rejection, StockItem, Reservation
│   ├── event/            InventoryDomainEvent (sealed), StockReserved, StockRejected
│   ├── exception/        InventoryDomainException, InvalidQuantityException,
│   │                     InvalidProductIdException, InsufficientStockException
│   └── repository/       StockItemRepository, ReservationRepository
├── application/
│   ├── ReserveStockService, ReserveStockCommand, ReserveStockOutcome
│   └── port/             OutboundMessage, OutboundMessageFactory, OutboundMessagePublisher,
│                         PublishFailedException, OutcomeMessageRepository,
│                         OutboundDeadLetterRepository, ProcessedEventRepository,
│                         ProcessedEvent, ProcessedOutcome
├── messaging/
│   ├── OrderPlacedListener
│   ├── OrderPlacedMessageParser, InvalidMessageException
│   └── dto/              OrderPlacedMessage, OrderPlacedItem
└── infrastructure/
    ├── config/           InventoryProperties (@ConfigurationProperties), ClockConfiguration,
    │                     TransactionConfiguration
    ├── persistence/      *Entity classes, Spring Data repositories, adapters implementing the
    │                     domain and application ports, mappers
    └── kafka/            KafkaConfiguration (existing; adds DefaultErrorHandler + backoff),
                          KafkaListenerStarter (existing), ConsumedDeadLetterRecoverer,
                          KafkaOutboundMessagePublisher, JsonOutboundMessageFactory,
                          message/StockReservedMessage, StockRejectedMessage (+ item records)

src/main/resources/
├── application.yml             (existing) + inventory.* properties, producer timeouts, retry
├── application-local.yml       adds classpath:db/local to Flyway locations
├── db/migration/V2__reserve_stock_schema.sql
└── db/local/R__seed_local_stock.sql

src/test/java/com/ecommerce/inventory/
├── domain/                     *Test (pure JUnit)
├── application/                ReserveStockServiceTest (Mockito)
├── messaging/                  OrderPlacedMessageParserTest, OrderPlacedListenerTest
├── infrastructure/
│   ├── persistence/            *IT (adapters, constraints, ordered locking, concurrency)
│   └── kafka/                  ConsumedDeadLetterRecovererIT, JsonOutboundMessageFactoryTest
├── component/                  ReserveStockComponentIT (happy, rejection, redelivery, duplicate
│                               order, invalid message, publish failure)
└── support/                    PostgresTestContainer, KafkaTestContainer, test producer/consumer

docs/adr/0004-outcome-stored-with-reservation.md    ADR 0004 + index entry
README.md, CLAUDE.md                                 seed products, local profile, tables, topics
pom.xml                                              spring-boot:run activates the local profile
```

**Structure Decision**: Single Maven project with the four constitutional layers as top-level
packages (`messaging`, `application`, `domain`, `infrastructure`) and feature-neutral sub-packages
inside them. Tests mirror the main packages; integration and component tests are `*IT` (Failsafe).

## Delivery outside this repository

- `ecommerce-infra/docs/events.md`: the four changes listed in
  [contracts/kafka-events.md](contracts/kafka-events.md#changes-required-in-ecommerce-infradocseventsmd),
  as a linked PR in `ecommerce-infra` (FR-022).

## Complexity Tracking

No constitution violations to justify.
