# Implementation Plan: Read-Only Products API with Available Stock (US-04)

**Branch**: `feature/US-04-products-api` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-products-api/spec.md`

## Summary

A new `web` layer, next to `messaging`, exposes the read-only catalog allowed by constitution 1.1.1
(Principle II, ADR 0007). `ProductController` serves `GET /products` and `GET /products/{id}`. Each
method calls exactly one method of `ProductQueryService` (`list(limit, offset)`, `get(productId)`) and
maps the result to response records (`ProductResponse`, `ProductPageResponse`).

The service reads through a read-only port, `ProductCatalogReader`, inside a read-only transaction
(`REPEATABLE_READ` for the list, so `items` and `total` share one snapshot). It takes no locks, so
reads never delay reservations. An unknown id throws `ProductNotFoundException`. The infrastructure
adapter uses the existing Spring Data stock repository: one native query ordered by
`product_id collate "C"` with `limit`/`offset`, plus `count()`. It maps entities to `ProductView`
records explicitly.

`V5__product_name.sql` adds `stock_items.name`: added with a default, backfilled from `product_id`,
default dropped, never blank. The local repeatable seed upserts the five sample names without
touching quantities.

Errors are RFC 9457 problem details from a `@RestControllerAdvice` that extends
`ResponseEntityExceptionHandler`: `404` for an unknown product, `400` with per-parameter `errors`
for bad `limit`/`offset`, `405` for write methods, and a generic `500`. Bad input never produces a
`5xx`. CORS origins come from `CORS_ALLOWED_ORIGINS` (empty means none; wildcards fail startup), and
springdoc 2.10.0 serves `/v3/api-docs` and `/swagger-ui.html`. Details: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (`-Xlint:all -Werror`)

**Primary Dependencies**: Spring Boot 3.5.16. `spring-boot-starter-web` and `-validation` are already
present. New: `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.10.0`, which the Boot BOM does not
manage, so its version goes in a `springdoc-openapi.version` property. springdoc 2.10.0 is built
against Boot 3.5.16 (R9).

**Storage**: PostgreSQL 16. `V5__product_name.sql` adds `stock_items.name`. The local seed
`db/local/R__seed_local_stock.sql` becomes an upsert of names (R10, R11).

**Testing**: JUnit 5, AssertJ, Mockito. `@WebMvcTest` with MockMvc for the controller. Testcontainers
PostgreSQL for integration tests. A component test with real PostgreSQL and Kafka uses a real HTTP
call (`RANDOM_PORT`) and Awaitility. JaCoCo 80% on `domain` and `application` (R12).

**Target Platform**: Linux container (Docker image, port 8080) and local JVM (port 8081)

**Project Type**: Single Maven project, an event-driven microservice with a read-only HTTP API

**Performance Goals**: A page of 200 or a single lookup in under 500 ms with 10,000 products (SC-002).
Reads never delay reservations (SC-004).

**Constraints**: No write endpoint. No locks on reads. Works with Kafka unreachable. Bad input never
produces a `5xx`. CORS only from the environment. No authentication.

**Scale/Scope**: Two endpoints, one migration, about 15 new main classes, about 8 new or changed test
classes

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

Constitution **1.1.2**. The request said 1.1.0; 1.1.1 (from `/speckit-clarify`) and 1.1.2 (from `/speckit-analyze`, finding C1) are PATCHes that add
the springdoc endpoints to Principle II.

| Principle | Gate | Pre-design | Post-design |
|---|---|---|---|
| I. Layers | Controller → application query service → port → persistence adapter. Domain untouched; JPA entities stay in `infrastructure.persistence` | ✅ | ✅ R1, data-model §2–3 |
| II. Read-only catalog API | Only `GET /products`, `GET /products/{id}`, health and the springdoc endpoints. No write endpoint (405). Controller calls exactly one service method. Response records separate from domain and JPA. Lock-free reads of `stock_items`. CORS only from `CORS_ALLOWED_ORIGINS`, empty default. Port 8081 via `SERVER_PORT` | ✅ | ✅ R1–R9, contract |
| III. Write flow | Not touched: the catalog writes nothing. Reservation and release flows unchanged; `saveAll` updates only quantity and time, so `name` survives | ✅ | ✅ R10 |
| IV. Stock integrity | No change to stock rules or locking (ADR 0002). The read path takes no locks. The `CHECK` stays | ✅ | ✅ R2, R3 (lock query order unchanged) |
| V. Type safety | Records for every DTO and read model. `Optional` from `findById`. Typed `ProductNotFoundException`. Bean Validation on request parameters. No `null` returns | ✅ | ✅ R4, data-model §3 |
| VI. Test coverage | `ProductQueryService` has happy-path and error tests for each method | ✅ | ✅ R12 |
| VII. Testing strategy | Unit, integration (real PostgreSQL), component (real PostgreSQL + Kafka, Awaitility), and API tests (MockMvc and a real HTTP call) | ✅ | ✅ R12 |
| VIII. Kafka | No event change. `events.md` untouched. Startup still never waits for Kafka, and the API works without it | ✅ | ✅ R14, smoke test |
| IX. Branching | `feature/US-04-products-api` from `main`. Commits `feat(US-04): …` | ✅ | ✅ |
| X. Quality gate | Four checks after every task. springdoc's annotations must compile under `-Xlint:all -Werror` without weakening the flags | ✅ | ⚠ Risk tracked (R9): if this fails, stop and report; no flag change |
| Tech stack / ADRs | Spring Web is in the stack table. springdoc is new but duplicates no capability; it is named in Documentation Standards, so no new ADR is needed | ✅ | ✅ |
| Documentation | Javadoc on all public types. `package-info.java` for `web` and `web.dto`. OpenAPI descriptions on endpoints, parameters and fields. README updated | ✅ | ✅ |

No violations; Complexity Tracking stays empty.

**Deviations from the request** (both explained in research):

- **No class-level `@Validated` (R4).** In Spring 6.1+ it moves validation to an AOP proxy that throws
  `ConstraintViolationException`, which is a `500` by default. The same `@Min`/`@Max` on
  `@RequestParam`, without `@Validated`, are enforced by MVC's built-in method validation and give a
  typed `400`.
- **Extra `QueryParameterGuard` (R5)**, because Spring silently accepts an empty or repeated `limit`
  and `offset`, and the spec makes those a `400`.

## Project Structure

### Documentation (this feature)

```text
specs/004-products-api/
├── plan.md                  # This file
├── research.md              # Phase 0: R1–R14
├── data-model.md            # Phase 1
├── quickstart.md            # Phase 1: manual validation
├── contracts/
│   └── products-api.md      # HTTP contract (matches the generated OpenAPI)
├── checklists/
│   └── requirements.md      # Spec quality checklist
└── tasks.md                 # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
pom.xml                                         + springdoc-openapi-starter-webmvc-ui 2.10.0 (property)
src/main/java/com/ecommerce/inventory/
├── web/                                        NEW top-level package (+ package-info.java)
│   ├── ProductController.java                  GET /products, GET /products/{id}; OpenAPI annotations
│   ├── ApiExceptionHandler.java                @RestControllerAdvice extends ResponseEntityExceptionHandler
│   ├── QueryParameterGuard.java                HandlerInterceptor: empty/repeated limit|offset → 400
│   ├── InvalidQueryParameterException.java
│   ├── WebMvcConfiguration.java                registers QueryParameterGuard for /products
│   └── dto/                                    (+ package-info.java)
│       ├── ProductResponse.java
│       ├── ProductPageResponse.java
│       └── ParameterError.java
├── application/
│   ├── ProductQueryService.java                NEW list(limit, offset), get(productId); read-only tx
│   ├── ProductView.java                        NEW read-model record
│   ├── ProductPage.java                        NEW read-model record
│   ├── ProductNotFoundException.java           NEW
│   └── port/ProductCatalogReader.java          NEW read-only port
├── infrastructure/
│   ├── config/
│   │   ├── InventoryProperties.java            + http.cors.allowed-origins (trim, no wildcard)
│   │   ├── WebCorsConfiguration.java           NEW WebMvcConfigurer: CORS for /products, /products/**
│   │   └── OpenApiConfiguration.java           NEW API title/description
│   └── persistence/
│       ├── StockItemEntity.java                + name (read-only)
│       ├── StockItemJpaRepository.java         + findPageOrderedByProductId (native, collate "C")
│       └── ProductCatalogReaderAdapter.java    NEW implements ProductCatalogReader
src/main/resources/
├── application.yml                             + inventory.http.cors, springdoc.*
├── db/migration/V5__product_name.sql           NEW
└── db/local/R__seed_local_stock.sql            names + upsert of name only
src/test/java/com/ecommerce/inventory/
├── application/ProductQueryServiceTest.java    NEW (Mockito)
├── web/ProductControllerTest.java              NEW (@WebMvcTest)
├── infrastructure/config/CorsPropertiesTest.java NEW
├── infrastructure/persistence/
│   ├── ProductCatalogReaderAdapterIT.java      NEW
│   ├── ProductNameMigrationIT.java             NEW
│   ├── LocalSeedIT.java                        names, quantities kept
│   └── SchemaMigrationIT.java                  insert with name
├── component/ProductCatalogComponentIT.java    NEW (reserve → GET shows reduced stock → release)
├── ApplicationHealthIT.java                    + catalog and docs answer with Kafka unreachable
└── support/
    ├── AbstractComponentIT.java                RANDOM_PORT + HTTP helper
    └── StockFixtures.java                      name column; given(id, name, qty)
README.md, CLAUDE.md                            HTTP API, curl, samples, snapshot caveat, CORS, public catalog
```

**Structure Decision**: A single Maven module, as before. `web` is a new inbound adapter package next
to `messaging`, mirroring how listeners are organised. The CORS and OpenAPI wiring goes in
`infrastructure/config` like the other Spring configuration. `QueryParameterGuard` and its
registration stay in `web` because they are request I/O.

## Implementation order (input for /speckit-tasks)

1. **Dependency**: add springdoc and run the full gate. This confirms that `-Xlint:all -Werror` holds
   (R9 risk) before any other code.
2. **Schema**: V5 migration, entity `name`, `StockFixtures` and `SchemaMigrationIT` updates,
   `ProductNameMigrationIT`.
3. **Local seed**: names upsert, `LocalSeedIT`.
4. **Application**: `ProductView`, `ProductPage`, `ProductNotFoundException`, `ProductCatalogReader`,
   `ProductQueryService`, and `ProductQueryServiceTest`.
5. **Persistence adapter**: the paged native query, `ProductCatalogReaderAdapter`, and
   `ProductCatalogReaderAdapterIT` (order, slices, total, exact case, no lock wait).
6. **Web**: DTOs, controller, `ApiExceptionHandler`, `QueryParameterGuard`, and
   `ProductControllerTest` for shape, 404, 400 cases, 405 and 500.
7. **CORS**: properties, `WebCorsConfiguration`, `CorsPropertiesTest`, and the CORS cases in
   `ProductControllerTest`.
8. **OpenAPI**: `OpenApiConfiguration`, annotations, springdoc properties; docs endpoints asserted in
   `ApplicationHealthIT`.
9. **Component**: `AbstractComponentIT` on `RANDOM_PORT`, then `ProductCatalogComponentIT`.
10. **Docs**: README (HTTP API section with curl examples, sample products with names, snapshot
    caveat, public catalog, port, `CORS_ALLOWED_ORIGINS`, Swagger UI, layout tree, migrations list),
    CLAUDE.md ("Where things are" for US-04, tables, test notes), and quickstart validation.

## Follow-ups outside this repository

- **ecommerce-infra compose**, once Inventory is added there: map host `8081` to container `8080` and
  set `CORS_ALLOWED_ORIGINS` to the frontend origin.
- `ecommerce-infra/docs/events.md`: no change (no event contract change).

## Complexity Tracking

No constitution violations to justify.
