# Implementation Plan: Retry Delivery of Inventory Events That Failed to Publish (US-03)

**Branch**: `feature/US-03-outbound-retry` | **Date**: 2026-11-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-outbound-retry/spec.md`

## Summary

A scheduled background worker (`@Scheduled` with a fixed delay, so rounds never overlap) drains the
existing `outbound_dead_letters` table. Each round claims up to `batch-size` due rows **one per
transaction** with `FOR UPDATE SKIP LOCKED`, republishes the stored message unchanged through the
existing Kafka publisher, and records the outcome on the row: Resolved (new `resolved_at` column),
failed attempt with exponential backoff, or Exhausted after the maximum attempts. A round stops at its
first failure so an unreachable broker costs one bounded attempt per round. The worker never runs on a
Kafka listener thread, contains every exception, and stops cleanly on shutdown. The US-01/US-02 upsert is
fixed so a Resolved event that fails again becomes due again. Same design as ecommerce-orders ADR 0005.
Details: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (`-Xlint:all -Werror`)

**Primary Dependencies**: Spring Boot 3.5.16 (scheduling, spring-kafka, JDBC), Flyway. No new dependency.

**Storage**: PostgreSQL 16; `V4__outbound_retry.sql` adds `outbound_dead_letters.resolved_at` and a check

**Testing**: JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL, Kafka), Awaitility, JaCoCo 80%

**Target Platform**: Linux container; local run on Windows via `mvnw.cmd`

**Project Type**: Event-driven backend microservice

**Performance Goals**: due rows delivered within one interval after the broker returns (SC-001); one
round with an unreachable broker costs at most one publish timeout

**Constraints**: each row processed by one instance at a time; one transaction per row; never block
consumers; no second dead-letter mechanism; settings from environment with defaults

**Scale/Scope**: one table change, ~8 new or changed production classes, no new topics

No `NEEDS CLARIFICATION` remains (research R1–R11).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | How this plan complies | Pre | Post |
|---|---|---|---|---|
| I | DDD & layers | Scheduler (infrastructure) triggers `OutboundRetryService` (application), which orchestrates only; the retry decision is the pure `OutboundRetryPolicy`; persistence behind the extended `OutboundDeadLetterRepository` port. The stock domain is untouched. | ✅ | ✅ |
| II | No API | No HTTP surface; manual replay is out of scope. | ✅ | ✅ |
| III | Write flow | Business state was committed by US-01/US-02 before the row existed. The worker's transaction only guards the dead-letter row: publish while holding its lock, then commit the row outcome. A failed commit after a successful publish causes a duplicate with the same event id, never a lost outcome; no business change is ever published before its commit. | ✅ | ✅ |
| IV | Stock integrity | Not affected; stock is never touched by the worker. | ✅ | ✅ |
| V | Types & validation | Enums for status; validated `@ConfigurationProperties`; `Optional` from `claimNextDue`; no `Throwable` catch (the round catches `RuntimeException` and logs it). | ✅ | ✅ |
| VI | Test coverage | Unit tests for the policy and every service path. | ✅ | ✅ |
| VII | Testing strategy | Unit, integration (migration, claim with SKIP LOCKED, upsert transitions, 3-worker concurrency), component (real Kafka delivery, backoff, exhaustion). | ✅ | ✅ |
| VIII | Kafka | Exactly the mechanism the principle describes: due rows claimed with `FOR UPDATE SKIP LOCKED`, republished with the same `eventId`, exponential backoff, marked Resolved or Exhausted, at-least-once. Startup without Kafka preserved. | ✅ | ✅ |
| IX | Branching | `feature/US-03-outbound-retry` from `main`; `feat(US-03): …`. | ✅ | ✅ |
| X | Build & quality | Four-step gate after every task. | ✅ | ✅ |

**Result**: PASS before and after design. **ADR 0006** records the worker design (one row per
transaction, no lease, fail-fast rounds, fixed-delay scheduling), mirroring Orders ADR 0005.

## Project Structure

### Documentation (this feature)

```text
specs/003-outbound-retry/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/kafka-events.md   (no contract change)
├── checklists/requirements.md
└── tasks.md                    (/speckit-tasks)
```

### Source Code (changes and additions)

```text
src/main/java/com/ecommerce/inventory/
├── application/
│   ├── OutboundRetryService, OutboundRetryPolicy, RetryRoundResult          (new)
│   └── port/OutboundDeadLetter (new), OutboundDeadLetterRepository (extended)
└── infrastructure/
    ├── config/InventoryProperties (+OutboundRetry), ApplicationPolicyConfiguration (+policy bean)
    ├── scheduling/OutboundRetryScheduler (+ package-info)  (new, @EnableScheduling, SmartLifecycle
    │                                                         stopping first on shutdown)
    └── persistence/OutboundDeadLetterRepositoryAdapter (claim, mark*, FR-012 upsert)

src/main/resources/
├── application.yml          inventory.outbound-retry.*
└── db/migration/V4__outbound_retry.sql

src/test/java/com/ecommerce/inventory/
├── application/   OutboundRetryPolicyTest, OutboundRetryServiceTest, OutboundRetryConcurrencyIT
├── infrastructure/persistence/  OutboundRetryMigrationIT, DeadLetterAdaptersIT (+claim, mark, FR-012)
├── component/     OutboundRetryComponentIT
└── support/       AbstractComponentIT (worker enabled, short interval); AbstractPostgresIT and
                   ApplicationHealthIT (worker disabled)

docs/adr/0006-outbound-retry-worker.md (+ index); README.md, CLAUDE.md
```

**Structure Decision**: Same single project and layers; the worker is an application service with an
infrastructure trigger, reusing the existing publisher and dead-letter store.

## Complexity Tracking

No constitution violations to justify.
