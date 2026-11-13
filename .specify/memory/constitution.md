<!--
SYNC IMPACT REPORT
==================
Version change:    1.0.0 → 1.0.1
Bump rationale:    PATCH — clarification only, no behaviour change. Principle III step 5 listed
                   "any outbound dead-letter record" inside the single write transaction, which
                   contradicted step 6 and Principle VIII: an outbound dead-letter record only
                   exists after a publish fails, and publishing happens after the commit. Found by
                   /speckit-analyze on specs/001-reserve-stock (finding C1).

Modified principles:
  - III. Write Flow Integrity (NON-NEGOTIABLE) — title unchanged. Step 5 now lists stock
    changes, reservation, the outcome message to be published, and the processed-event record.
    New paragraph: on a failed post-commit publish, the outbound dead-letter record is written in
    its own transaction (Principle VIII) and committed state is never rolled back.

Added sections:
  - (none)

Removed sections:
  - (none)

Templates reviewed:
  ✅ .specify/templates/plan-template.md  — Constitution Check gates derive from this file at runtime; no static change needed.
  ✅ .specify/templates/spec-template.md  — Generic structure; no change needed.
  ✅ .specify/templates/tasks-template.md — No principle-driven task type added or removed.
  ✅ .specify/templates/commands/*.md     — Directory not present in this project; nothing to update.

Runtime guidance:
  ✅ CLAUDE.md                            — "Write flow" line aligned with the amended step 5.
  ✅ docs/adr/0001-postgresql-for-inventory.md — transaction contents corrected, amendment noted.
  ✅ README.md, Dockerfile, docs/adr/README.md, ADR 0002 — created by the project bootstrap
     (were listed as pending in the 1.0.0 report).
  ✅ specs/001-reserve-stock (spec FR-017, research R3/R7, tasks T036/T056–T057) — already match
     the clarified wording; no change needed.

Migration note:
  - None. No code exists yet that writes the outbound dead-letter record.

Previous amendments:
  - 1.0.0 (2026-11-13): initial ratification from the template, modelled on ecommerce-orders
    v1.1.0 and adapted to Java 21 / Spring Boot 3.x.

Deferred TODOs:
  (none)
-->

# ecommerce-inventory Constitution

`ecommerce-inventory` is the Java microservice that owns product stock and stock reservations in
a polyglot e-commerce system (Orders: NestJS, Payments: .NET, Notifications: Python). The shared
event contracts in `ecommerce-infra/docs/events.md` are the source of truth for every topic and
payload this service consumes or produces.

## Core Principles

### I. Domain-Driven Design & Layered Architecture

The codebase MUST follow a strict four-layer model:
**Messaging (Kafka listeners) → Application service → Domain → Repository**.

- **Listeners** handle I/O only — deserialization, validation, and calling exactly one
  application service method. They MUST NOT contain logic.
- **Application services** orchestrate workflows and own transaction boundaries. They MUST NOT
  contain business rules.
- **Domain** is the sole home of all business logic: aggregates, value objects and domain events.
  The domain package MUST NOT depend on Spring, JPA (`jakarta.persistence`) or Kafka; it is plain
  Java.
- **Repositories** abstract persistence behind domain-facing interfaces. JPA entities MUST NOT
  leak into the domain: the domain model and the persistence model are separate classes, mapped
  explicitly in the infrastructure layer.

Aggregates:
- `StockItem` — `productId`, available quantity.
- `Reservation` — `id`, `orderId`, items, status (`RESERVED`, `REJECTED`, `RELEASED`).

Domain events (`StockReserved`, `StockRejected`, `StockReleased`) are raised by the domain and
published by infrastructure.
Placing business logic in listeners, application services or repositories is a constitutional
violation and MUST be rejected in code review.

### II. Event-Driven Service, No API (NON-NEGOTIABLE)

The service MUST NOT expose a REST, GraphQL or any other HTTP business API in its initial scope.

- All input arrives as Kafka events; all output leaves as Kafka events.
- Spring Boot Actuator health (and readiness/liveness) endpoints are the only permitted HTTP
  surface.
- Adding any HTTP API requires an accepted ADR in `docs/adr/` **before** implementation starts,
  followed by a constitution amendment.

### III. Write Flow Integrity (NON-NEGOTIABLE)

Every state change MUST follow this exact sequence and MUST NOT deviate:

1. Consume the Kafka message.
2. Deserialize and validate it into an inbound DTO (Principle V).
3. The listener calls one application service method.
4. The domain executes business logic and raises domain events.
5. Persist to **PostgreSQL in a single transaction**: stock changes, the reservation, the
   outcome message to be published, and the processed-event record.
6. **Only after** a successful commit: publish the resulting Kafka event.
7. **Only after** the DB save: commit the consumer offset.

Publishing to Kafka before the database commit is a critical violation. If the transaction rolls
back, no Kafka message MUST be emitted and the offset MUST NOT be committed. Publishing MUST be
triggered after commit (e.g. `@TransactionalEventListener(phase = AFTER_COMMIT)` or explicit
post-commit code), never from inside the transaction. `ReservationStatus` transitions are only
final once persisted.

If the post-commit publish fails, the outbound dead-letter record is written in its own
transaction (Principle VIII); the committed state is never rolled back.

### IV. Stock Integrity (NON-NEGOTIABLE)

- Available stock MUST NEVER be negative. This MUST be enforced in the domain **and** by a
  database `CHECK (available_quantity >= 0)` constraint.
- A reservation is atomic per order: all items are reserved or none are. A rejected order MUST
  NOT change any stock.
- An order referencing an unknown `productId` MUST be rejected as a whole.
- Concurrent reservations MUST be safe: stock rows are locked (`SELECT … FOR UPDATE`) or
  conditionally updated in a **deterministic order (ascending `productId`)** to prevent deadlocks
  and lost updates. The chosen strategy MUST be recorded in an ADR.
- A reservation is tied to exactly one `orderId`; at most one reservation MUST exist per
  `orderId` (enforced by a unique constraint).
- A reservation MUST be released at most once. Releasing an order that was never reserved,
  was rejected, or is already released is a no-op that MUST be logged and MUST NOT change stock
  or emit an event.

### V. Type Safety & Input Validation

- Java 21, compiled with `-Xlint:all -Werror`: compiler warnings are build errors.
- Raw types are banned. Unchecked casts MUST NOT be used unless suppressed at the narrowest scope
  with a written justification.
- Public methods MUST NOT return `null`; use `Optional` or throw a typed exception.
- Every inbound message MUST be parsed into an immutable `record` DTO validated with Jakarta Bean
  Validation (`@NotNull`, `@Positive`, `@NotEmpty`, `@Valid`, …). An invalid message MUST NOT be
  partially processed.
- All statuses MUST be enums (`ReservationStatus`, …); bare string literals for status are
  prohibited.
- Domain errors MUST be domain exception types (e.g. `InsufficientStockException`,
  `UnknownProductException`). Exceptions MUST NOT be swallowed; catching `Throwable` is
  prohibited; no silent failures.

### VI. Test Coverage

All domain logic and application services MUST have unit tests.

- Every domain method and application service method MUST have at least one test covering the
  happy path and at least one covering an error/edge case.
- Domain tests MUST be pure JUnit 5 — no Spring context, no database, no Kafka.
- Application service tests MAY mock repositories and publishers (Mockito) and assert with AssertJ.
- A PR that adds or changes domain or application behaviour without corresponding tests MUST NOT
  be merged.

### VII. Testing Strategy

Three test layers are required:

- **Unit tests** — domain and application layers in isolation.
- **Integration tests** — repositories and Flyway migrations against a real PostgreSQL via
  Testcontainers (no H2 or in-memory substitutes).
- **Component tests** — full Spring Boot context with real PostgreSQL and real Kafka via
  Testcontainers. Other services are simulated by producing their events to Kafka. They MUST cover
  the complete flow: event consumed → DB updated → event published.

Tools: JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL, Kafka), Awaitility for asynchronous
assertions (`Thread.sleep` in tests is prohibited).
Minimum coverage: **80%** line coverage on the domain and application layers, enforced by a JaCoCo
`check` rule bound to `verify` — the build fails below the threshold.
Test names MUST describe the behaviour, e.g.
`should reject the whole order when one item has insufficient stock` (via `@DisplayName` or a
descriptive method name).

### VIII. Kafka Events (Producer & Consumers)

Kafka is the event bus between services. This service both **consumes** and **produces** events.
`ecommerce-infra/docs/events.md` is the source of truth for every topic and payload.

**Consumed topics:**
- `orders.order-placed` — reserve stock for the order.
- `orders.order-cancelled` — release the order's stock reservation, if any.

**Produced topics:**
- `inventory.stock-reserved`
- `inventory.stock-rejected`
- `inventory.stock-released`

**Event payload rules:**
- Every event MUST include `eventId` (UUID), `occurredAt` (ISO 8601 timestamp), `aggregateId`
  (UUID) and `version` (integer).
- For `inventory.*` events, `aggregateId` is the **reservation id**; the order is identified by
  the separate `orderId` field.
- For consumed `orders.*` events, `aggregateId` is the order id.
- The Kafka message key MUST be the event's `aggregateId`.
- Payloads MUST be serialized as JSON.
- Breaking changes to a payload schema require a new topic version
  (e.g. `inventory.stock-reserved.v2`).
- Event classes MUST carry Javadoc on every field and MUST match `ecommerce-infra/docs/events.md`.
  Any change to an event MUST update that file in the same change set (a linked PR in
  `ecommerce-infra`).

**Producer failure handling:**
- If publishing fails after the DB commit, the error MUST be logged with full context (topic,
  key, `eventId`, `orderId`, cause) and the event MUST be stored in an outbound dead-letter table
  with `status`, `retryCount`, `nextRetryAt` and the last error.
- A scheduled retry worker MUST claim due rows with `SELECT … FOR UPDATE SKIP LOCKED`, republish
  with the **same** `eventId`, back off exponentially, and mark each row `RESOLVED` or `EXHAUSTED`.
- Delivery is **at-least-once**; consumers downstream rely on `eventId` for deduplication.

**Consumer rules:**
- Consumers MUST be idempotent: events are deduplicated by `eventId` via a processed-events table
  written in the same transaction as the state change. Processing the same event twice MUST
  produce the same result.
- Offsets MUST be committed only after the database save has succeeded (manual acknowledgement).
- Failed processing MUST be retried with exponential backoff. Once retries are exhausted, the
  message MUST be stored in a consumed-message dead-letter table, the failure logged with full
  context, and the offset committed so the partition is not blocked.
- Consumers MUST NOT contain business logic (Principle I).

**Startup resilience:**
- The service MUST start and report healthy when Kafka is unreachable: no broker connection is
  required at boot, listener containers connect in the background, and producers connect lazily.
  Kafka unavailability MUST NOT fail the application context.

### IX. Branching Strategy

This project follows a **feature-branch workflow** tied to user stories, identical to
ecommerce-orders.

**Branch naming convention:**
- Feature branches: `feature/US-XX-short-description`
- Bug fixes: `fix/US-XX-short-description`
- Infrastructure: `chore/short-description`

Examples:
- `feature/US-01-reserve-stock`
- `feature/US-02-release-stock`

**Rules:**
- `main` is always production-ready — it MUST NOT receive direct commits, except the initial
  repository setup.
- Every user story gets its own branch created from `main` before any implementation begins.
- Each branch maps to exactly one user story.
- PRs MUST reference the user story: "Implements US-01: Reserve stock".
- Branches MUST be deleted after merge.
- Commit messages MUST follow Conventional Commits with a scope:
  `feat(US-01): add reserve stock handler`
  `test(US-01): add unit tests for reservation aggregate`
  `docs(US-01): add Javadoc to stock reserved event`

**Automated branch creation (MANDATORY):**
When /speckit.specify is invoked for a user story, Claude Code MUST:
1. Ensure the working tree is clean (no uncommitted changes)
2. Checkout main and pull latest: `git checkout main && git pull`
3. Create and checkout the feature branch: `git checkout -b feature/US-XX-short-description`
4. Only then proceed with generating the spec

The branch MUST exist before any spec file is written.
Claude Code MUST NOT generate spec files while on main.

### X. Build & Code Quality Integrity (NON-NEGOTIABLE)

The project MUST compile and pass all quality checks at all times.

**After every task implementation, Claude Code MUST run in this order, using the Maven wrapper:**

1. `./mvnw -B -q compile`          — compilation MUST pass (warnings are errors)
2. `./mvnw -B -q checkstyle:check` — Checkstyle MUST report zero violations
3. `./mvnw -B -q spotless:check`   — formatting MUST be consistent
4. `./mvnw -B verify`              — unit, integration and component tests and the JaCoCo
   coverage gate MUST pass

**Rules:**
- If any of the above fails, Claude Code MUST fix it before moving to the next task.
- A failing build, Checkstyle violation or formatting violation MUST never be committed to the
  branch.
- Compiler and Checkstyle warnings are blocking (`-Werror`, Checkstyle `failOnViolation` with
  `violationSeverity=warning`) — not deferred items.
- Claude Code MUST NOT mark a task as complete until all four checks pass.

**Tooling:**
- Build: Maven via the Maven wrapper (`mvnw`, `.mvn/wrapper/`)
- Linter: Checkstyle
- Formatter: Spotless (Maven plugin)
- Tests: JUnit 5 (Surefire for unit, Failsafe for integration/component), JaCoCo for coverage

**Configuration files required:**
- `pom.xml` — compiler `release` 21 with `-Xlint:all -Werror`; Checkstyle, Spotless, Surefire,
  Failsafe and JaCoCo plugins configured
- Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`)
- `checkstyle.xml` — Checkstyle rules
- Spotless configuration (in `pom.xml`)
- `.editorconfig`
- `.gitignore` for Java/Maven

## Technology Stack

| Concern | Choice |
|---------|--------|
| Runtime | Java 21 |
| Framework | Spring Boot 3.x |
| Build | Maven with Maven wrapper |
| Messaging | Kafka via Spring Kafka |
| Persistence | Spring Data JPA + PostgreSQL |
| Migrations | Flyway |
| Validation | Jakarta Bean Validation |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL, Kafka), Awaitility, JaCoCo |
| Lint / Format | Checkstyle + Spotless |
| Containerization | Docker (multi-stage `Dockerfile`) |
| Local infrastructure | ecommerce-infra (docker compose): PostgreSQL `localhost:5433`, database `inventory`, user `inventory_user` / `inventory_pass`; Kafka `localhost:9092` |

Schema changes MUST be made only through Flyway migrations; `spring.jpa.hibernate.ddl-auto` MUST be
`validate` or `none`.
Introducing a new runtime dependency that duplicates a capability already provided by the stack
above requires an ADR in `docs/adr/` before the PR can be merged.

## Documentation Standards

All public classes, methods and interfaces MUST carry Javadoc comments.
Kafka event payloads MUST be documented in the event class Javadoc (every field) and in
`ecommerce-infra/docs/events.md`, which remains the source of truth.
Each top-level package MUST include a `package-info.java` describing its responsibility.
Architectural decisions MUST be recorded as ADRs in `docs/adr/` using the standard template
(Context / Decision / Consequences).
ADRs are numbered sequentially with a four-digit prefix (`0001-short-title.md`, `0002-...`), are
never renumbered, and are indexed in `docs/adr/README.md`. A superseded ADR is kept and marked as
superseded by the newer one.
The repository MUST keep `README.md`, `CLAUDE.md` and the multi-stage `Dockerfile` up to date with
the current behaviour and this constitution.

## Governance

This constitution supersedes all other project conventions and style guides. When a conflict
arises, the constitution takes precedence.

**Amendment procedure**: Any amendment requires (1) a draft PR updating this file, (2) a version
bump per the policy below, (3) approval from at least one other maintainer, and (4) a migration
note if existing code must change.

**Versioning policy**:
- MAJOR: Removal or redefinition of an existing principle (breaking governance change).
- MINOR: Addition of a new principle or materially expanded guidance.
- PATCH: Clarifications, wording refinements, typo fixes, non-semantic changes.

**Compliance**: Every PR review MUST verify that no constitutional principle is violated.
Reviewers are empowered to block merges for violations without exception. New principles take
effect immediately upon merge.

**Version**: 1.0.1 | **Ratified**: 2026-11-13 | **Last Amended**: 2026-11-13
