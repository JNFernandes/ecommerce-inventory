# Research: Read-Only Products API with Available Stock (US-04)

Decisions for the plan. Each entry: decision, rationale, alternatives considered.

## R1. Layering and packages

**Decision**: A new top-level package `web` sits next to `messaging` (the inbound adapters).

- `web/ProductController`: `GET /products` → `ProductQueryService.list(limit, offset)`;
  `GET /products/{id}` → `ProductQueryService.get(productId)`. One service call per method, plus
  mapping to response records. No logic.
- `web/dto/ProductResponse`, `web/dto/ProductPageResponse`: response records.
- `web/ApiExceptionHandler`: `@RestControllerAdvice`; all errors become RFC 9457 problem details (R6).
- `web/QueryParameterGuard`: rejects empty or repeated paging parameters (R5).
- `application/ProductQueryService`, `application/ProductView`, `application/ProductPage`,
  `application/ProductNotFoundException`, `application/port/ProductCatalogReader`.
- `infrastructure/persistence/ProductCatalogReaderAdapter` implements the port.
- `infrastructure/config/WebCorsConfiguration` (CORS, R8) and `infrastructure/config/OpenApiConfiguration`
  (API title and description, R9).

The domain is unchanged. `StockItem` keeps only what the reservation rules need. A product name is
catalog data, not a stock rule, so the read model carries it and the aggregate does not.

**Rationale**: Constitution Principle I (layers) and II (controllers do I/O only, response records
separate from domain and JPA classes). The query side does not go through the domain because there is
no business rule to apply. The `ProductCatalogReader` port keeps the application layer free of JPA.

**Alternatives**: (a) The controller calls a Spring Data repository directly. Rejected because it
breaks Principle II. (b) Add `name` to the `StockItem` aggregate and reuse `StockItemRepository`.
Rejected: the repository only offers locking reads (ADR 0002), and the domain does not need names.
(c) Put `ProductNotFoundException` in `domain/exception`. Rejected: it is a query outcome, not a
broken business rule.

## R2. Read-only transactions without locks

**Decision**: `ProductQueryService.list` uses
`@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`, and `get` uses
`@Transactional(readOnly = true)`. The queries are plain `SELECT`s, with no `FOR UPDATE`, no
`PESSIMISTIC_*` lock mode, no `lock_timeout` and no `OrderLock`.

**Rationale**: FR-015 and Principle II. PostgreSQL reads are MVCC snapshots: they take no row locks
and never wait for, or delay, the `PESSIMISTIC_WRITE` locks of a reservation. A reader sees only
committed stock, never a partial decision. `REPEATABLE_READ` makes the page and `total` come from
one snapshot, so `total` cannot disagree with the items of the same response; it costs nothing for a
read-only transaction. `readOnly` makes Spring set Hibernate's flush mode to MANUAL and the JDBC
connection read-only, so the catalog cannot write even by mistake.

**Alternatives**: (a) Programmatic `TransactionOperations`, as the write services use. Rejected:
those services need explicit post-commit control (publish after commit), and a read has nothing to
do after commit. (b) No transaction. Rejected: the page and the count would come from two different
snapshots.

## R3. Paged query, ordering and count

**Decision**: `StockItemJpaRepository` gets one native query:

```sql
select * from stock_items order by product_id collate "C" limit :limit offset :offset
```

`total` comes from `JpaRepository.count()`. `get` uses `findById` (exact, case-sensitive equality on
the primary key). The adapter maps each `StockItemEntity` to `ProductView` explicitly; entities never
leave `infrastructure.persistence`.

**Rationale**: FR-003 asks for a character order that does not depend on the database locale. The
ecommerce-infra PostgreSQL uses `en_US.utf8`, which would sort `sku-1` and `SKU-1` together and ignore
punctuation. `collate "C"` gives byte order, the same as Java `String.compareTo` for ASCII ids. Spring
Data `Pageable` only supports page numbers (offset = page × size). Any other offset, such as
`offset=7&limit=5`, needs explicit `limit`/`offset`. Sorting 10,000 short keys is well under SC-002's
500 ms.

**Alternatives**: (a) Change the `product_id` column collation to `"C"` in V5 so the primary key index
serves the order. Rejected for now: it rebuilds the primary key and changes the order of the existing
lock query (ADR 0002), which is not needed at this size. Revisit if the catalog grows past about 100k
products. (b) Spring Data scroll API (`ScrollPosition.offset`). Rejected: its offset semantics changed
between Spring Data versions and are easy to get off by one. (c) A JPQL query with
`setFirstResult`/`setMaxResults` in a custom fragment. Equivalent, but more code than one annotated
query.

## R4. Validating `limit` and `offset`

**Decision**: Use Spring MVC's built-in method validation (Spring Framework 6.1+). The parameters are
`@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit` and
`@RequestParam(defaultValue = "0") @Min(0) int offset`, with **no class-level `@Validated`**. A
violation raises `HandlerMethodValidationException`, and a non-integer raises
`MethodArgumentTypeMismatchException`. Both are mapped to `400` (R6). The bounds are constants on the
controller (`DEFAULT_LIMIT`, `MAX_LIMIT`), reused in the OpenAPI descriptions.

`ProductQueryService.list` also checks its arguments (`limit` 1..200, `offset` ≥ 0) and throws
`IllegalArgumentException` otherwise. This guards other callers and is covered by unit tests.

**Rationale**: The request asked for `@Validated` with `@Min`/`@Max`. The Spring reference documents
that a class-level `@Validated` switches validation to the AOP proxy, which throws
`ConstraintViolationException`. That exception is not a web exception, becomes a `500` unless handled
separately, and turns off the built-in support. Without `@Validated`, the same `@Min`/`@Max`
annotations are enforced by MVC itself and produce a typed `400` with per-parameter results.
FR-010 and FR-011 are met with less code.

**Alternatives**: (a) Class-level `@Validated` plus a handler for `ConstraintViolationException`.
Rejected: two validation mechanisms, and parameter names are parsed from property paths. (b) Accept
the parameters as `String` and parse them in the controller. Rejected: that is logic in the
controller.

## R5. Empty and repeated parameters (`?limit=`, `?limit=1&limit=2`)

**Decision**: `QueryParameterGuard`, a `HandlerInterceptor` registered for `/products`, rejects a
request whose `limit` or `offset` is present but blank, or appears more than once. It throws
`InvalidQueryParameterException` (in `web`) listing every offending parameter. `ApiExceptionHandler`
maps it to `400` with the same `errors` format as R4.

**Rationale**: FR-010. Spring silently applies `defaultValue` to an empty parameter and silently uses
the first value when a single-valued parameter is repeated. Both would hide client bugs. Exceptions
thrown in `preHandle` go through the same exception resolvers as controller exceptions.

**Alternatives**: (a) Relax the spec and accept Spring's behaviour. Rejected: the spec makes these
`400` and the cost is one small class. (b) A servlet `Filter`. Rejected: it would bypass the
`@RestControllerAdvice` and need its own error writing.

## R6. Error bodies: RFC 9457 problem details

**Decision**: `ApiExceptionHandler extends ResponseEntityExceptionHandler` (`@RestControllerAdvice`),
so every Spring MVC exception (`405`, `404` for unknown paths through `NoResourceFoundException`,
`400` for type mismatches and method validation) already yields a `ProblemDetail` with
`application/problem+json`. Overrides and additions:

| Cause | Status | `title` | Extra properties |
|---|---|---|---|
| `ProductNotFoundException` | 404 | `Product not found` | `productId` |
| `HandlerMethodValidationException` | 400 | `Invalid request parameters` | `errors[]` |
| `MethodArgumentTypeMismatchException` | 400 | `Invalid request parameters` | `errors[]` |
| `InvalidQueryParameterException` (R5) | 400 | `Invalid request parameters` | `errors[]` |
| `HttpRequestMethodNotSupportedException` | 405 | Spring default | `Allow` header |
| any other `Exception` | 500 | `Internal error` | none; `detail` is generic; logged at ERROR with the cause |

Each `errors[]` element is `{ "parameter", "rejectedValue", "message" }`, for example
`{"parameter":"limit","rejectedValue":"201","message":"must be less than or equal to 200"}`. The
`instance` field is the request path. Stack traces, SQL and class names never appear (FR-012):
`server.error.include-stacktrace` and `include-message` keep Boot's `never` defaults, and the 500
handler writes a fixed `detail`.

**Rationale**: The clarification chose Spring's standard problem-detail body. Extending
`ResponseEntityExceptionHandler` is the documented way to get it for all MVC exceptions while
customizing the ones that matter. Spring Boot's `spring.mvc.problemdetails.enabled` registers the
same base class and backs off when one exists, so it stays unset.

**Alternatives**: A custom error record. Rejected by the clarification.

**To verify in tests**: an unknown path (`/products/SKU-1/reservations`) returns a problem detail and
not Boot's `/error` JSON. If it doesn't, set `spring.mvc.problemdetails.enabled=false` and map
`NoResourceFoundException` explicitly. Already covered because `ResponseEntityExceptionHandler`
handles `NoResourceFoundException` since 6.1.

## R7. Methods: GET only (plus HEAD and OPTIONS)

**Decision**: The controller declares only `@GetMapping` methods. Spring answers `POST`, `PUT`,
`PATCH` and `DELETE` with `405` and an `Allow: GET,HEAD,OPTIONS` header (R6), and handles `HEAD` and
`OPTIONS` automatically. A `@WebMvcTest` asserts `405` for each write method.

**Rationale**: FR-013 and Principle II.

## R8. CORS from `CORS_ALLOWED_ORIGINS`

**Decision**: `inventory.http.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:}` binds to a
`List<String>` in a new `InventoryProperties.Http.Cors` record. Its compact constructor trims each
entry, drops blanks, and **fails startup** if an entry is `*` or contains a wildcard. When the list
is not empty, `WebCorsConfiguration` (`WebMvcConfigurer.addCorsMappings`) registers `/products` and
`/products/**` with `allowedOrigins(list)`, `allowedMethods("GET", "HEAD")`, `allowCredentials(false)`
and `maxAge(1800)`. When it is empty, no mapping is registered.

Behaviour:

- Allowed origin: the response carries `Access-Control-Allow-Origin: <origin>`, and a preflight
  `OPTIONS` gets `200` with the allow headers.
- Origin not allowed while a mapping exists: Spring rejects the request with `403`.
- No origins configured: no CORS headers are ever sent, so browsers block cross-origin calls.
  Same-origin and server-to-server calls (no `Origin` header) work.

**Rationale**: FR-023 and Principle II (origins only from the environment variable, empty by
default, no wildcards). Spring's CORS preflight handling covers `OPTIONS`, so `OPTIONS` does not need
to be in `allowedMethods`. The user's "GET and OPTIONS" is met. The documentation endpoints are
same-origin tools and get no CORS mapping.

**Alternatives**: `@CrossOrigin` on the controller. Rejected: it cannot read a list from the
environment cleanly or fail on wildcards.

## R9. OpenAPI documentation with springdoc

**Decision**: Add `org.springdoc:springdoc-openapi-starter-webmvc-ui` **2.10.0**, in a
`springdoc-openapi.version` property. The Spring Boot BOM does not manage springdoc. 2.10.0 is the
newest 2.x release, and its own build uses Spring Boot 3.5.16, the exact parent of this project; the
3.x line targets Spring Boot 4. Configuration:

```yaml
springdoc:
  paths-to-match: /products, /products/**
  api-docs.path: /v3/api-docs
  swagger-ui.path: /swagger-ui.html
```

`OpenApiConfiguration` sets the title, version and description. The description says the API is
public, read-only and returns a stock snapshot. The controller carries `@Tag`, `@Operation`,
`@Parameter` and `@ApiResponse` (200/400/404/405/500 with the `ProblemDetail` schema). Response
records carry `@Schema` on every component, with descriptions and examples.

**Rationale**: The clarification chose springdoc, and constitution 1.1.1 permits these two read-only
endpoints. springdoc builds the document on the first request, so it needs neither Kafka nor the
database at startup. `paths-to-match` keeps Actuator out of the document.

**Risks**: (a) The build uses `-Xlint:all -Werror`. If the swagger-annotations jars cause a
`classfile` or `processing` warning, the first task fails at `compile`. Mitigation: add the
dependency in its own task, run the gate immediately, and stop and report rather than weaken the lint
flags (a constitution rule). (b) The Swagger UI web jar adds about 5 MB to the image. Accepted.

## R10. Schema change: `stock_items.name`

**Decision**: `V5__product_name.sql`, as requested:

```sql
alter table stock_items add column name varchar(200) not null default '';
update stock_items set name = product_id;
alter table stock_items alter column name drop default;
alter table stock_items add constraint ck_stock_items_name_not_blank check (btrim(name) <> '');
```

`StockItemEntity` gets a read-only `name` field (`@Column(nullable = false, length = 200)`), with no
setter. The write path loads and updates managed entities (`saveAll` only changes quantity and time),
so reservations and releases never touch the name.

**Rationale**: FR-017 and FR-018. Adding a column with a constant default is metadata-only in
PostgreSQL 11+. The backfill runs in the same migration transaction, so no row is ever without a name.
Dropping the default makes every future insert state a name.

**Impact on existing tests**: `StockFixtures.given(productId, quantity)` and the
`SchemaMigrationIT` check-constraint insert must include `name`. `given` uses the product id as the
name, and a new overload `given(productId, name, quantity)` sets one explicitly.

## R11. Local seed with names (idempotent upsert)

**Decision**: `db/local/R__seed_local_stock.sql` inserts `(product_id, name, available_quantity,
updated_at)` with the five names from the clarification, and uses
`on conflict (product_id) do update set name = excluded.name`. Quantities of existing rows are never
changed. Changing the file's content makes Flyway re-run this repeatable migration once on existing
local databases. Repeatable migrations run after versioned ones, so V5 always runs first.
`db/migration` contains no sample data.

**Rationale**: FR-019. The current seed's `do nothing` would leave existing local products named
after their ids.

## R12. Tests

| Layer | Test | Covers |
|---|---|---|
| Unit (Mockito) | `ProductQueryServiceTest` | page found, empty page, `total` passed through, limit/offset passed to the port, product found, `ProductNotFoundException`, `IllegalArgumentException` for out-of-range arguments |
| Unit | `CorsPropertiesTest` (in `InventoryPropertiesTest` style) | trimming, blanks, empty default, wildcard rejected |
| Web slice (`@WebMvcTest(ProductController.class)` + `@Import` of the advice, guard and CORS config; service `@MockitoBean`) | `ProductControllerTest` | 200 list shape and defaults; 200 item; 404 problem body with `productId`; 400 for `limit=0`, `limit=201`, `offset=-1`, `limit=abc`, `offset=1.5`, `limit=`, repeated `limit`, both invalid (two `errors`); 405 for POST/PUT/PATCH/DELETE; 500 without internal details; CORS allowed origin, disallowed origin (403), preflight `OPTIONS`; no CORS headers when no origins are configured |
| Integration (`AbstractPostgresIT`) | `ProductCatalogReaderAdapterIT` | order by product id in byte order (`SKU-002` < `SKU-010` < `sku-001`), limit/offset slices, offset past the end, `total`, exact-case `findById`, a read that does not wait for a row locked `FOR UPDATE` by another transaction |
| Integration | `ProductNameMigrationIT` | Flyway to V4, insert rows, migrate to V5: `name = product_id`, `NOT NULL`, blank rejected, no default left |
| Integration | `LocalSeedIT` (extended) | the five names; an existing local row keeps its quantity and gets its name |
| Smoke (`ApplicationHealthIT`, Kafka at `localhost:1`) | extended | `GET /products` 200, `/v3/api-docs` 200 and `/swagger-ui.html` reachable with Kafka unreachable (FR-021) |
| Component (`AbstractComponentIT`) | `ProductCatalogComponentIT` | stock 10 → `orders.order-placed` for 2 → Awaitility until `GET /products/{id}` shows 8 → `orders.order-cancelled` → shows 10 again (SC-003) |

`AbstractComponentIT` switches to `webEnvironment = RANDOM_PORT` and gains a small
`TestRestTemplate` helper. This is a change in the base class, so all component tests still share one
context (CLAUDE.md rule). JaCoCo's 80% gate covers `application`, and `ProductQueryService` is fully
unit-tested.

## R13. Observability

**Decision**: No per-request logging (catalog reads are frequent and not business events). The `500`
handler logs at ERROR with the request path and the exception. Actuator health is unchanged.

**Rationale**: The constitution only asks for logs on failures. Request metrics (`http.server.requests`)
are available through Micrometer if Actuator metrics are exposed later; that is out of scope.

## R14. Event contracts and ecommerce-infra

**Decision**: `ecommerce-infra/docs/events.md` is **not** changed. It documents Kafka events, and
this feature adds no event and changes no contract. The README documents the HTTP API.

Follow-ups for the ecommerce-infra docker compose, once Inventory runs there (not part of this
repository):

- publish container port `8080` on host port `8081` (kafka-ui keeps `8080`);
- set `CORS_ALLOWED_ORIGINS` to the frontend origin (for example `http://localhost:5173`).

These are recorded in `quickstart.md` and the README.
