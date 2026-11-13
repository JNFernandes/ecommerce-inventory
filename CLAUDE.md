# CLAUDE.md

Inventory microservice: Java 21, Spring Boot 3.5, DDD, event-driven only. The source of truth for
every rule below is `.specify/memory/constitution.md`. If this file and the constitution disagree,
the constitution wins.

## Stack

- Java 21, Spring Boot 3.5, Maven **through the wrapper only** (Maven is not installed: use
  `.\mvnw.cmd` on Windows, `./mvnw` elsewhere)
- Spring Kafka, Spring Data JPA + PostgreSQL, Flyway, Jakarta Bean Validation
- JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL, Kafka), Awaitility, JaCoCo
- Checkstyle (`checkstyle.xml`) and Spotless (palantir-java-format)
- Local infra comes from the `ecommerce-infra` repo (`docker compose up -d` there): PostgreSQL
  `localhost:5433` (db `inventory`, `inventory_user` / `inventory_pass`), Kafka `localhost:9092`
- Local HTTP port is `8081` (kafka-ui uses 8080); the Docker image listens on `8080`
- On this machine `JAVA_HOME` may point at JDK 17. Set it to a JDK 21 before running the wrapper,
  e.g. `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'`

## Quality gate (after every task, before every commit)

Run these in order. All four must pass. Fix any failure before moving on.

```powershell
.\mvnw.cmd -B -q compile            # -Xlint:all -Werror: every compiler warning is an error
.\mvnw.cmd -B -q checkstyle:check   # zero violations; warnings are blocking
.\mvnw.cmd -B -q spotless:check     # run .\mvnw.cmd -B -q spotless:apply to fix formatting
.\mvnw.cmd -B verify                # unit (*Test) + integration/component (*IT) + JaCoCo 80% gate
```

Never commit a failing build, a Checkstyle violation or a formatting violation. A task is not done
until all four pass. `verify` needs Docker running (Testcontainers).

## Layering

**Messaging (Kafka listeners) → Application service → Domain → Repository.**

- Listeners (`messaging`): deserialize into an immutable `record` DTO, validate it with Bean
  Validation, call exactly one application service method, acknowledge after the DB save. No logic.
- Application services (`application`): orchestrate and own the transaction. No business rules.
- Domain (`domain`): **all** business logic. Plain Java: no Spring, no `jakarta.persistence`, no
  Kafka imports. Aggregates `StockItem` and `Reservation`; events `StockReserved`,
  `StockRejected`, `StockReleased`.
- Repositories / persistence (`infrastructure`): JPA entities are separate from domain objects and
  mapped explicitly. JPA entities never reach the domain.
- No business HTTP API (no REST, no GraphQL). Actuator health is the only HTTP surface. Adding an
  API needs an ADR first.

## Write flow (never deviate)

consume → validate → application service → domain → **one PostgreSQL transaction** (stock,
reservation, outcome message to publish, processed-event record) → **after commit** publish to
Kafka → **after the DB save** acknowledge the offset. Never publish inside the transaction. If the
publish fails, write the outbound dead-letter record in its own transaction; never roll back the
committed state.

## Stock rules

- Stock never goes negative: enforced in the domain **and** by a DB `CHECK` constraint.
- All items of an order are reserved, or none. A rejected order changes no stock. An unknown
  product rejects the whole order.
- Lock stock rows with `PESSIMISTIC_WRITE` in ascending `productId` order through the single
  repository method for it (ADR 0002). Never lock stock rows one by one.
- One reservation per `orderId` (unique constraint). Release at most once; releasing something
  never reserved, rejected or already released is a logged no-op.

## Event contracts

- Source of truth: `../ecommerce-infra/docs/events.md`. Any event change updates that file too.
- Consumed: `orders.order-placed` (reserve), `orders.order-cancelled` (release).
- Produced: `inventory.stock-reserved`, `inventory.stock-rejected`, `inventory.stock-released`.
- Envelope: `eventId` (UUID), `occurredAt` (ISO 8601), `aggregateId` (UUID), `version` (int). JSON.
- `inventory.*`: `aggregateId` = reservation id, order in `orderId`. `orders.*`: `aggregateId` =
  order id. Kafka key = `aggregateId`.
- Consumers: idempotent by `eventId` (processed-events table, same transaction), retry with
  exponential backoff, then consumed-message dead-letter table and commit the offset.
- Producer failure after commit: log with full context, store in the outbound dead-letter table;
  the retry worker uses `FOR UPDATE SKIP LOCKED` and republishes with the same `eventId`.
- Startup never waits for Kafka: `spring.kafka.listener.auto-startup=false` and
  `KafkaListenerStarter` starts the containers in the background on `ApplicationReadyEvent`.
  Don't add code that connects to Kafka during context refresh.

## Code rules

- No raw types; no unchecked casts without a justified, narrowly scoped `@SuppressWarnings`.
- Public methods never return `null`: use `Optional` or throw.
- Statuses are enums (`ReservationStatus`), never string literals.
- Domain errors are domain exception types. Never swallow exceptions, never catch `Throwable` /
  `Error`, never `System.out` / `printStackTrace` (Checkstyle enforces these).
- Javadoc on every public class, method and interface; every top-level package has a
  `package-info.java`.
- Schema changes only through new Flyway migrations (`V<n>__description.sql`); never edit an
  applied one. `ddl-auto` stays `validate`.

## Tests

- Domain: pure JUnit 5 + AssertJ (no Spring, DB or Kafka). Application: Mockito mocks allowed.
- Each domain method and application service method: at least one happy path and one error/edge
  case.
- Integration (`*IT`): repositories and migrations on real PostgreSQL (Testcontainers, no H2).
- Component (`*IT`): full context, real PostgreSQL and Kafka; simulate other services by producing
  events; assert asynchronously with Awaitility (no `Thread.sleep`).
- Names describe behaviour: `@DisplayName("should reject the whole order when one item has
  insufficient stock")`.

## Branches and commits

- Branches: `feature/US-XX-short-description`, `fix/US-XX-short-description`,
  `chore/short-description`. One branch per user story, created from an up-to-date `main` before
  any work. Never commit to `main`. Delete branches after merge.
- `/speckit.specify`: clean tree → `git checkout main && git pull` → create the feature branch →
  only then write the spec. Never write spec files on `main`.
- Conventional Commits with scope: `feat(US-01): add reserve stock handler`,
  `test(US-01): ...`, `docs(US-01): ...`; `chore:`, `build:`, `docs:` for non-story work.
- PRs reference the story: "Implements US-01: Reserve stock".

## Docs

- ADRs in `docs/adr/` (`NNNN-short-title.md`, never renumbered, indexed in `docs/adr/README.md`,
  superseded ones kept). Current: 0001 PostgreSQL, 0002 ordered pessimistic locks, 0003 parallel
  saga participation.
- Keep `README.md`, this file and the `Dockerfile` up to date with behaviour changes.
