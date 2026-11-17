---

description: "Task list for US-04 Read-Only Products API with Available Stock"
---

# Tasks: Read-Only Products API with Available Stock (US-04)

**Input**: Design documents from `specs/004-products-api/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/products-api.md](contracts/products-api.md),
[quickstart.md](quickstart.md)

**Tests**: Required by constitution Principles VI and VII and by the request: unit tests (Mockito) for
the query service, `@WebMvcTest` for the web layer, integration tests on real PostgreSQL, and a
component test on real PostgreSQL + Kafka with a real HTTP call. Each task delivers its code and its
tests together, so every task is one green commit.

**Organization**: The tasks follow the order requested for `/speckit-tasks`: migration and sample
data → query service → JPA adapter → controller and error handling → CORS and port → springdoc →
component test → docs. Each task carries the spec story it serves (`[US1]` browse the list, `[US2]`
look up one product, `[US3]` bad requests, `[US4]` browser access, operation and docs). These labels
are the stories inside this spec; `US-04` is the whole feature.

**One deviation from the requested order**: the JPA adapter (T005) comes **before** the query service
(T006). `ProductQueryService` is a Spring bean that needs a `ProductCatalogReader` bean. Without the
adapter, every full-context test (`AbstractPostgresIT`, `ApplicationHealthIT`, component tests) would
fail to start, and the `verify` gate of the service task would be red.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- Paths: `M/` = `src/main/java/com/ecommerce/inventory/`, `T/` = `src/test/java/com/ecommerce/inventory/`,
  `R/` = `src/main/resources/`

## Rules that apply to every task

- **Gate**: every task ends with the full quality gate, run with JDK 21 on `JAVA_HOME`
  (`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'`), in this order:
  1. `.\mvnw.cmd -B -q compile`
  2. `.\mvnw.cmd -B -q checkstyle:check`
  3. `.\mvnw.cmd -B -q spotless:check` (fix with `.\mvnw.cmd -B -q spotless:apply`)
  4. `.\mvnw.cmd -B verify` (Docker must be running)

  All four must pass before the task is checked off and committed. Never weaken a compiler, Checkstyle
  or coverage setting to get a green gate; stop and report instead.
- **One commit per task**, Conventional Commits with scope `US-04` (`feat(US-04): …`,
  `test(US-04): …`, `docs(US-04): …`).
- Javadoc on every public type, method and record component; `package-info.java` for every new
  package (`web`, `web.dto`). Public methods never return `null`.
- **Read-only**: no code path in this feature writes to the database. No locks on catalog reads (no
  `@Lock`, no `FOR UPDATE`, no `OrderLock`, no `lock_timeout`).
- The domain package (`M/domain/`) is not touched.
- Component tests: every spy, mock and property override stays in `T/support/AbstractComponentIT`.
- Test names describe behaviour with `@DisplayName("should …")`.

---

## Phase 1: Setup

No setup task. `spring-boot-starter-web` and `spring-boot-starter-validation` are already
dependencies. The only new dependency, springdoc, is added in T016, in the position requested for
this task list.

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the `name` column, the sample names and the application read-model types that all
stories use.

- [X] T001 Add product names to the schema. Create `R/db/migration/V5__product_name.sql` exactly as in
  research R10:
  1. `alter table stock_items add column name varchar(200) not null default '';`
  2. `update stock_items set name = product_id;`
  3. `alter table stock_items alter column name drop default;`
  4. `alter table stock_items add constraint ck_stock_items_name_not_blank check (btrim(name) <> '');`

  Add a header comment that points to `specs/004-products-api/data-model.md` §1. In
  `M/infrastructure/persistence/StockItemEntity.java`, add
  `@Column(name = "name", nullable = false, length = 200) private String name;` with a package-private
  getter `getName()` and **no setter**.

  Update `T/support/StockFixtures.java` so tests can still insert rows:
  - `given(productId, quantity)` inserts `name = productId`.
  - A new `given(String productId, String name, int availableQuantity)` sets the name, and its upsert
    also updates `name`.
  - Add `Optional<String> name(String productId)`.

  In `T/infrastructure/persistence/SchemaMigrationIT.java`, add a `name` value to the raw insert that
  checks `ck_stock_items_available_not_negative`, so it still fails on that constraint and not on
  `NOT NULL`. Grep `src/test` for any other `insert into stock_items` and give each a name.
  Gate. Commit `feat(US-04): add a non-null name to stock items`.
- [X] T002 Prove the backfill. Create `T/infrastructure/persistence/ProductNameMigrationIT.java`. It is
  plain JUnit, with no Spring context. Use the database from
  `PostgresTestContainer.isolatedDatabase("product_name_migration")` with the container's credentials,
  and run Flyway with `Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")`:
  1. `.target("4").load().migrate()`;
  2. insert `SKU-A` (qty 3) and `SKU-B` (qty 0) **without** a name;
  3. migrate again with no target.

  Tests:
  - (a) "should name every existing product after its product id": `name = product_id`, and the
    quantities are unchanged.
  - (b) "should refuse a product without a name": an insert without `name` fails with `NOT NULL`,
    proving no default is left.
  - (c) "should refuse a blank name": `'   '` fails with `ck_stock_items_name_not_blank`.

  If `isolatedDatabase` reuses an existing database, drop and recreate its schema first
  (`flyway.clean()` is disabled by default, so use `drop schema public cascade; create schema
  public;` through JDBC). Gate. Commit `test(US-04): prove product names are backfilled by V5`.
- [X] T003 [P] Give the local sample products their names (serves Story 4, FR-019). **Done in the same
  commit as T001**: with the `local` profile, Flyway runs V5 and then the seed, so a seed without
  `name` fails `NOT NULL` and T001 alone cannot pass the gate (decided with the user during
  implementation). Rewrite
  `R/db/local/R__seed_local_stock.sql` to insert `(product_id, name, available_quantity, updated_at)`
  for:

  | `product_id` | `name` | `available_quantity` |
  |---|---|---|
  | `SKU-001` | Wireless Mouse | 10 |
  | `SKU-002` | Mechanical Keyboard | 25 |
  | `SKU-003` | USB-C Hub | 100 |
  | `SKU-004` | 27-inch Monitor | 5 |
  | `SKU-005` | Webcam HD | 0 |

  Use `on conflict (product_id) do update set name = excluded.name`. Never update the quantity. Keep
  the "local only" header and state that names are upserted and quantities never reset.

  Extend `T/infrastructure/persistence/LocalSeedIT.java`:
  - "should give the five sample products their names".
  - "should name an existing sample product without changing its stock": set `SKU-003` to quantity 7
    and name `SKU-003`, re-run the seed SQL (read it from the classpath and execute it with
    `JdbcTemplate`, as the existing test does), then expect name `USB-C Hub` and quantity 7.

  Adapt the existing assertions to the new column. Gate. Commit
  `feat(US-04): name the local sample products`.
- [X] T004 [P] Add the application read model and port (data-model §3), all in `M/application/`:
  - `ProductView`: record `(String productId, String name, int availableQuantity)`. The compact
    constructor rejects a null or blank id or name, and a negative quantity, with
    `IllegalArgumentException`.
  - `ProductPage`: record `(List<ProductView> items, long total, int limit, int offset)`. It applies
    `List.copyOf(items)` and rejects `total < 0`, `limit` outside 1..200, `offset < 0` and
    `items.size() > limit`.
  - `ProductNotFoundException`: extends `RuntimeException`, with `productId()` and the message
    `Product <id> not found`.
  - `port/ProductCatalogReader`: interface with `List<ProductView> findPage(int limit, int offset)`,
    `long count()` and `Optional<ProductView> findById(String productId)`, each Javadoc'd as
    "read-only, takes no locks".

  Add `public static final int MAX_LIMIT = 200` on `ProductPage`; T006 reuses it. Mention the read
  model in `M/application/package-info.java` and `M/application/port/package-info.java`. Create
  `T/application/ProductReadModelTest.java` (pure JUnit + AssertJ) with valid construction, each
  rejected value, and the defensive copy of `items`. JaCoCo checks `application` at 80%. Gate.
  Commit `feat(US-04): add the product read model and catalog port`.

**Checkpoint**: gate green. The schema has names, the local seed is named, and the read-model types
exist. There is no behaviour change yet.

---

## Phase 3: User Stories 1 and 2 — Browse the catalog and look up one product (P1) 🎯 MVP

**Goal**: `GET /products` (paged, ordered, with `total`) and `GET /products/{id}` (200 or 404) return
names and current available quantities.

**Independent test**: with stock rows in place, the list returns them in product id byte order with
`total`/`limit`/`offset`; one product returns 200; an unknown or wrongly cased id returns a 404
problem body.

- [X] T005 [US1] Implement the read adapter (research R3). In
  `M/infrastructure/persistence/StockItemJpaRepository.java`, add:

  ```java
  @Query(value = "select * from stock_items order by product_id collate \"C\" limit :limit offset :offset", nativeQuery = true)
  List<StockItemEntity> findPageOrderedByProductId(@Param("limit") int limit, @Param("offset") int offset);
  ```

  Create `M/infrastructure/persistence/ProductCatalogReaderAdapter.java`, a public `@Repository`
  implementing `ProductCatalogReader`:
  - `findPage` maps each entity with a private `toView(StockItemEntity)` →
    `new ProductView(productId, name, availableQuantity)`;
  - `count` delegates to `jpaRepository.count()`;
  - `findById` uses `jpaRepository.findById(id).map(this::toView)`.

  No locks, no `lock_timeout`, no transaction annotations (the service owns the transaction).
  Entities never leave the package.

  Create `T/infrastructure/persistence/ProductCatalogReaderAdapterIT.java` (extends
  `AbstractPostgresIT`; clean with `DatabaseCleaner`; arrange with `StockFixtures`). Tests:
  - (a) "should list products in product id byte order": given `sku-001`, `SKU-010`, `SKU-002`,
    `SKU-001`, expect `SKU-001`, `SKU-002`, `SKU-010`, `sku-001`.
  - (b) "should return the requested slice": 5 products, `findPage(2, 3)` → products 4 and 5.
  - (c) "should return an empty page past the end": `findPage(50, 10)`.
  - (d) "should count every product".
  - (e) "should find a product by its exact id": exact match only, `sku-003` ≠ `SKU-003`.
  - (f) "should map name and available quantity".
  - (g) "should read stock while another transaction holds the row lock": in a separate thread, open
    a transaction through a `TransactionTemplate`, run `select … for update` on `SKU-001` and hold
    it until a latch is released. Meanwhile `findById("SKU-001")` and `findPage(50, 0)` must return
    within 2 s (`assertTimeoutPreemptively`). Release the latch in `finally`.

  Gate. Commit `feat(US-04): read the product catalog without locks`.
- [X] T006 [US1] Implement `ProductQueryService.list`. Create `M/application/ProductQueryService.java`
  (`@Service`, constructor `ProductQueryService(ProductCatalogReader reader)`) with
  `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ) public ProductPage list(int limit, int offset)`:
  - reject `limit < 1 || limit > ProductPage.MAX_LIMIT` or `offset < 0` with
    `IllegalArgumentException` before touching the reader;
  - otherwise return `new ProductPage(reader.findPage(limit, offset), reader.count(), limit, offset)`.

  Javadoc must state "snapshot, no locks; items and total come from one snapshot".

  Create `T/application/ProductQueryServiceTest.java` (Mockito + AssertJ, no Spring). Tests:
  - "should return the page and total from the catalog";
  - "should pass limit and offset unchanged to the catalog" (`verify(reader).findPage(20, 40)`);
  - "should return an empty page with the total when the offset is past the end";
  - "should reject a limit of 0", "should reject a limit above 200", "should reject a negative
    offset", each with `verifyNoInteractions(reader)`.

  Gate. Commit `feat(US-04): add the product list query`.
- [X] T007 [US2] Implement `ProductQueryService.get`. In `M/application/ProductQueryService.java`, add
  `@Transactional(readOnly = true) public ProductView get(String productId)`: return
  `reader.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId))`. In
  `T/application/ProductQueryServiceTest.java`, add:
  - "should return the product when it exists";
  - "should throw ProductNotFoundException naming the id when the product is unknown" (assert
    `productId()` and the message).

  Gate. Commit `feat(US-04): add the single product query`.
- [ ] T008 [US1] Add the web layer for the list:
  - Create `M/web/package-info.java` ("inbound HTTP adapter: read-only catalog, I/O only;
    constitution Principle II") and `M/web/dto/package-info.java`.
  - Create `M/web/dto/ProductResponse.java`, a record `(String id, String name, int availableQuantity)`
    with a static `from(ProductView)`.
  - Create `M/web/dto/ProductPageResponse.java`, a record
    `(List<ProductResponse> items, long total, int limit, int offset)` with a static `from(ProductPage)`
    that uses `List.copyOf`.
  - Create `M/web/ProductController.java`: `@RestController @RequestMapping("/products")`, constants
    `DEFAULT_LIMIT = "50"` and `DEFAULT_OFFSET = "0"`, and
    `@GetMapping public ProductPageResponse list(@RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(ProductPage.MAX_LIMIT) int limit, @RequestParam(defaultValue = DEFAULT_OFFSET) @Min(0) int offset)`.
    It returns `ProductPageResponse.from(service.list(limit, offset))`.

  **No class-level `@Validated`** (research R4). No other logic.

  Create `T/web/ProductControllerTest.java` (`@WebMvcTest(ProductController.class)`, `MockMvc`,
  `@MockitoBean ProductQueryService`). Tests:
  - "should return the page with items, total, limit and offset": JSON paths `$.items[0].id`,
    `.name`, `.availableQuantity`, `$.total`, `$.limit`, `$.offset`, and exactly these four
    top-level fields;
  - "should apply limit 50 and offset 0 by default" (`verify(service).list(50, 0)`);
  - "should pass the requested limit and offset".

  If the slice cannot start because another `WebMvcConfigurer` or `@ControllerAdvice` in the
  application needs beans that aren't loaded, add only the missing `@Import`/`@MockitoBean` in the
  test. Gate. Commit `feat(US-04): expose GET /products`.
- [ ] T009 [US2] Add `GET /products/{id}` and the 404 problem body:
  - In `M/web/ProductController.java`, add
    `@GetMapping("/{id}") public ProductResponse get(@PathVariable String id)` →
    `ProductResponse.from(service.get(id))`.
  - Create `M/web/ApiExceptionHandler.java`: `@RestControllerAdvice` extending
    `ResponseEntityExceptionHandler`, with `@ExceptionHandler(ProductNotFoundException.class)`
    returning a `ProblemDetail`: status 404, title `Product not found`, detail
    `Product <id> not found.`, property `productId`. Spring fills `instance` with the request path.

  In `T/web/ProductControllerTest.java`, add:
  - "should return one product";
  - "should answer 404 with a problem body naming the missing product": content type
    `application/problem+json`; `$.status` 404, `$.title`, `$.detail`, `$.instance`
    `/products/SKU-999`, `$.productId`;
  - "should look up the id exactly as given", with `sku-003` passed unchanged to the service.

  Gate. Commit `feat(US-04): expose GET /products/{id} with a 404 problem body`.

**Checkpoint**: Stories 1 and 2 work through MockMvc, and the reads are proven lock-free on real
PostgreSQL. MVP.

---

## Phase 4: User Story 3 — Clear answers to bad requests (P2)

**Goal**: every invalid input is a `400` with per-parameter `errors`; write methods are `405`; nothing
leaks internals; bad input never produces a `5xx`.

**Independent test**: each bad `limit`/`offset` in the contract returns `400` with the right
`errors[]`; `POST`, `PUT`, `PATCH` and `DELETE` return `405`; an unexpected exception returns a
generic `500`.

- [ ] T010 [US3] Map validation and type errors to `400` (research R4, R6):
  - Create `M/web/dto/ParameterError.java`, a record `(String parameter, String rejectedValue, String message)`.
  - In `M/web/ApiExceptionHandler.java`, override `handleHandlerMethodValidationException`. Build one
    `ParameterError` per invalid parameter: the name from the `MethodParameter`, the rejected value
    as a string, and the message from the first constraint violation. Return a `ProblemDetail` with
    status 400, title `Invalid request parameters`, detail
    `One or more request parameters are invalid.` and property `errors`.
  - Override `handleTypeMismatch` the same way, for `MethodArgumentTypeMismatchException`, with the
    message `must be a whole number`.
  - Add a private helper so all `400`s share the title and detail.

  In `T/web/ProductControllerTest.java`, add a parameterized test "should reject an invalid paging
  parameter with 400 and a detail for it" for:

  | Query | Message |
  |---|---|
  | `limit=0` | `must be greater than or equal to 1` |
  | `limit=201` | `must be less than or equal to 200` |
  | `offset=-1` | `must be greater than or equal to 0` |
  | `limit=abc` | `must be a whole number` |
  | `offset=1.5` | `must be a whole number` |

  Add "should list a detail for each invalid parameter" (`limit=201&offset=-1` → two `errors`), and
  in every case `verifyNoInteractions(service)`. Gate. Commit
  `feat(US-04): answer invalid paging parameters with 400 problem details`.
- [ ] T011 [US3] Reject empty and repeated parameters (research R5):
  - Create `M/web/InvalidQueryParameterException.java` (holds a `List<ParameterError>`).
  - Create `M/web/QueryParameterGuard.java`, a `HandlerInterceptor`. In `preHandle`, for `limit` and
    `offset`: if `request.getParameterValues(name)` has more than one value, add
    `must be given at most once`; if it has one blank value, add `must not be empty`. Throw when the
    list is not empty.
  - Create `M/web/WebMvcConfiguration.java`, a `@Configuration` implementing `WebMvcConfigurer` that
    registers the guard for `/products`.
  - In `M/web/ApiExceptionHandler.java`, map `InvalidQueryParameterException` to the same `400`
    problem.

  In `T/web/ProductControllerTest.java` (`@Import(WebMvcConfiguration.class)` if the slice does not
  pick it up), add "should reject an empty limit" (`limit=`), "should reject a repeated limit"
  (`limit=1&limit=2`), "should reject an empty offset" and "should ignore unknown query parameters"
  (`foo=bar` → 200). Gate. Commit `feat(US-04): reject empty or repeated paging parameters`.
- [ ] T012 [US3] Write methods, unknown paths and unexpected errors. In
  `M/web/ApiExceptionHandler.java`, add `@ExceptionHandler(Exception.class)`: log at ERROR with the
  request path and the exception, and return a `ProblemDetail` with status 500, title
  `Internal error` and detail `The request could not be processed.`. Never include the exception
  message.

  Confirm in `R/application.yml` that `server.error.include-stacktrace` and `include-message` are not
  set (defaults `never`). In `T/web/ProductControllerTest.java`, add:
  - parameterized "should refuse <method> with 405 and change nothing" for `POST`, `PUT`, `PATCH` and
    `DELETE` on `/products` and `/products/SKU-001`: status 405, an `Allow` header containing `GET`,
    and `verifyNoInteractions(service)`;
  - "should answer 404 problem details for an unknown path" (`/products/SKU-001/reservations`);
  - "should answer 500 without internal details": the service throws
    `new IllegalStateException("SQL select * from secret")`; the body has no `SQL`, `Exception` or
    `secret`, and the title is `Internal error`.

  Gate. Commit `feat(US-04): refuse write methods and hide internal errors`.

**Checkpoint**: Story 3 works. Every row of the contract's error table is covered by a test.

---

## Phase 5: User Story 4 — Browser access, port, Kafka independence and docs (P2)

**Goal**: CORS only for configured origins, port 8081 by default, the catalog answering with Kafka
down, and OpenAPI docs at `/v3/api-docs` and `/swagger-ui.html`.

**Independent test**: an allowed origin gets `Access-Control-Allow-Origin`; another origin gets `403`;
with no origins configured, no CORS headers are sent; with Kafka unreachable, the catalog and docs
answer; the OpenAPI document lists only the two `/products` paths.

- [ ] T013 [P] [US4] Add the CORS setting (research R8):
  - In `M/infrastructure/config/InventoryProperties.java`, add the component `@Valid @NotNull Http http`
    with nested records `Http(@Valid @NotNull Cors cors)` and `Cors(List<String> allowedOrigins)`. The
    compact constructor of `Cors` maps `null` to an empty list, trims each entry, drops blanks, and
    throws `IllegalArgumentException("CORS_ALLOWED_ORIGINS must list explicit origins; wildcards are not allowed")`
    for any entry containing `*`. It stores `List.copyOf`.
  - In `R/application.yml`, add
    `inventory.http.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:}` with a comment: comma separated,
    empty = no cross-origin access.
  - Update every `new InventoryProperties(` call site (currently
    `T/infrastructure/kafka/KafkaOutboundMessagePublisherTest.java`).

  Create `T/infrastructure/config/CorsPropertiesTest.java`, modelled on `OutboundRetryPropertiesTest`
  (an `ApplicationContextRunner` binding `InventoryProperties`). Every test passes
  `CORS_ALLOWED_ORIGINS` explicitly with `withPropertyValues`, so a variable set in the developer's
  shell can never change the result. Tests:
  - "should allow no origin by default" (`CORS_ALLOWED_ORIGINS=`);
  - "should split, trim and drop blank origins" (`" http://a.test , ,https://b.test"`);
  - "should fail startup for a wildcard origin" (`*` and `https://*.example`).

  Gate. Commit `feat(US-04): read CORS origins from CORS_ALLOWED_ORIGINS`.
- [ ] T014 [US4] Apply CORS to the catalog. Create `M/infrastructure/config/WebCorsConfiguration.java`,
  a `@Configuration` implementing `WebMvcConfigurer`. In `addCorsMappings`, only when the origins
  list is not empty: register `/products` and `/products/**` with `allowedOrigins(list)`,
  `allowedMethods("GET", "HEAD")`, `allowCredentials(false)` and `maxAge(1800)`.

  In `T/web/ProductControllerTest.java`, add
  `@TestPropertySource(properties = "inventory.http.cors.allowed-origins=http://localhost:5173")` and
  `@Import(WebCorsConfiguration.class)`, plus `@EnableConfigurationProperties(InventoryProperties.class)`
  if the slice does not bind it. Tests:
  - "should allow a configured origin" (`Access-Control-Allow-Origin: http://localhost:5173`);
  - "should refuse an origin that is not configured" (`http://evil.example` → 403);
  - "should answer a preflight from a configured origin" (`OPTIONS` with
    `Access-Control-Request-Method: GET` → 200 and the allow-methods header).

  Create `T/web/ProductControllerWithoutCorsTest.java` (same slice, with
  `@TestPropertySource(properties = "inventory.http.cors.allowed-origins=")` set explicitly, so the
  result never depends on a `CORS_ALLOWED_ORIGINS` variable in the shell) with
  "should send no CORS headers when no origin is configured": a request with `Origin` gets 200 for
  same-path GET and no `Access-Control-Allow-Origin`. Gate. Commit
  `feat(US-04): allow cross-origin reads only from configured origins`.
- [ ] T015 [P] [US4] Check the port configuration (FR-024). Confirm that `R/application.yml` has
  `server.port: ${SERVER_PORT:8081}` and that `Dockerfile` keeps `ENV SERVER_PORT=8080` and
  `EXPOSE 8080`. Both are present today; change nothing if they still are.

  In `T/ApplicationHealthIT.java`, add "should serve the catalog with Kafka unreachable": with the
  existing `spring.kafka.bootstrap-servers=localhost:1`, `GET /products` and `GET /products/SKU-404`
  over the real port return 200 and a 404 problem body (FR-021, SC-006). Over the same real port,
  add the encoded-id edge cases:
  - "should look up an id with an encoded space": `GET /products/SKU%20X` → 404 problem body with
    `productId` `SKU X`;
  - "should refuse an encoded slash without a server error": `GET /products/a%2Fb` → 400, never
    `5xx`.

  Use `RestClient`/`TestRestTemplate` with a pre-encoded `URI` so the client does not re-encode `%`.
  Keep the existing health assertions unchanged. Gate. Commit `test(US-04): prove the catalog works while Kafka is down`.
- [ ] T016 [US4] Add the springdoc dependency (research R9). In `pom.xml`, add the property
  `<springdoc-openapi.version>2.10.0</springdoc-openapi.version>` and the dependency
  `org.springdoc:springdoc-openapi-starter-webmvc-ui:${springdoc-openapi.version}`. The Spring Boot
  BOM does not manage springdoc, and 2.10.0 is built against Spring Boot 3.5.16.

  Run the gate immediately. If `compile` fails because of a lint warning that comes from the new jars,
  **stop and report**; do not change `-Xlint:all -Werror` (constitution Principle X). Commit
  `build(US-04): add springdoc-openapi for the catalog documentation`.
- [ ] T017 [US4] Document the API with OpenAPI:
  - Create `M/infrastructure/config/OpenApiConfiguration.java`: an `OpenAPI` bean with title
    `ecommerce-inventory catalog`, the version from the build if available (otherwise `v1`), and a
    description saying the API is public, read-only, needs no authentication, and returns a stock
    snapshot whose authoritative check happens at reservation.
  - In `R/application.yml`, add `springdoc.paths-to-match: /products, /products/**`,
    `springdoc.api-docs.path: /v3/api-docs` and `springdoc.swagger-ui.path: /swagger-ui.html`.
  - Annotate `M/web/ProductController.java` with `@Tag`, and each method with `@Operation`,
    `@Parameter` (description, default, minimum and maximum for `limit`/`offset`; the case-sensitive
    exact match for `id`) and `@ApiResponse` for 200, 400, 404 (get only), 405 and 500, using
    `ProblemDetail` as the error schema.
  - Annotate every record component of `M/web/dto/*.java` with `@Schema(description = …, example = …)`.

  In `T/ApplicationHealthIT.java`, add:
  - "should serve the OpenAPI document of the catalog only": `/v3/api-docs` 200; `$.paths` has
    exactly `/products` and `/products/{id}`, and no `/actuator`;
  - "should serve the Swagger UI": `/swagger-ui.html` → 200 or a 302 to `/swagger-ui/index.html`,
    which answers 200.
  - "should map no HTTP endpoint beyond the permitted surface" (FR-016, constitution 1.1.2 Principle
    II): collect the patterns of every `RequestMappingInfoHandlerMapping` bean in the context
    (`getHandlerMethods().keySet()` → `getPatternValues()`). Assert they are a subset of
    `/products`, `/products/{id}`, `/v3/api-docs`, `/v3/api-docs.yaml`, `/v3/api-docs/swagger-config`,
    `/swagger-ui.html`, `/error` and the Actuator health paths. Also assert that `/actuator/info`,
    `/actuator/env` and `/actuator/beans` answer 404 over the real port. If springdoc maps an extra
    pattern under `/v3/api-docs/**` or `/swagger-ui/**`, list it explicitly and say why in a
    comment; never use a wildcard in the allowed set.

  Gate. Commit `feat(US-04): document the catalog with OpenAPI and Swagger UI`.

**Checkpoint**: Story 4 works. All four stories pass their independent tests.

---

## Phase 6: End-to-end (component)

- [ ] T018 [US1] Make component tests call the real HTTP port. In
  `T/support/AbstractComponentIT.java`, set
  `webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT` on the existing `@SpringBootTest`,
  autowire `TestRestTemplate`, and add helpers
  `protected ResponseEntity<String> httpGet(String path)` and
  `protected int availableQuantityFromApi(String productId)` (GET `/products/{id}`, read
  `availableQuantity` with Jackson's `ObjectMapper`). Changing the base class keeps a single shared
  context. Gate: all existing component tests must stay green. Commit
  `test(US-04): run component tests on a real HTTP port`.
- [ ] T019 [US1] Create `T/component/ProductCatalogComponentIT.java` (extends `AbstractComponentIT`).
  Test "should show reserved stock as unavailable and released stock as available again":
  1. `stock.given("SKU-CAT", "Catalog Item", 10)`;
  2. `GET /products/SKU-CAT` → 10 with the name;
  3. produce `orderPlaced(eventId, orderId, line("SKU-CAT", 2))`;
  4. `await().atMost(30 s).untilAsserted` until `availableQuantityFromApi("SKU-CAT")` is 8;
  5. produce `orderCancelled(newEventId, orderId)`;
  6. await until it is 10 again;
  7. `GET /products` contains `SKU-CAT` with 10 and `total` ≥ 1.

  No `Thread.sleep` (SC-003). Gate. Commit `test(US-04): prove the catalog follows reservations end to end`.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T020 [P] Update `README.md`:
  - **Intro**: "Status" adds US-04.
  - **HTTP API section**:
    - mark both endpoints and the docs endpoints *implemented*;
    - curl examples for list, paging, single product, 404 and 400, with sample responses from
      `contracts/products-api.md`;
    - the snapshot caveat: `availableQuantity` is a snapshot, and the authoritative check happens
      when the order is reserved;
    - "the catalog is public: no authentication";
    - CORS behaviour;
    - Swagger UI at `http://localhost:8081/swagger-ui.html`.
  - **Sample products table**: add the **name** column.
  - **Configuration table**: `CORS_ALLOWED_ORIGINS` with no "takes effect once…" note; `SERVER_PORT`
    "HTTP port (catalog, docs, health)".
  - **Database tables**: `stock_items` now holds the name.
  - **Project layout**: add `web/`, the new application classes, and `V5` in the migrations list.
  - **Docker**: a note on the ecommerce-infra compose follow-up (host 8081 → container 8080, set
    `CORS_ALLOWED_ORIGINS`).

  Gate. Commit `docs(US-04): document the products API`.
- [ ] T021 [P] Update `CLAUDE.md`:
  - "Where things are" gets a US-04 bullet: `web/ProductController` → `ProductQueryService` →
    `ProductCatalogReader` / `ProductCatalogReaderAdapter` (native page query
    `collate "C"`, no locks); `ApiExceptionHandler` (problem details, `errors[]`);
    `QueryParameterGuard`; `WebCorsConfiguration`; springdoc paths.
  - Tables: `stock_items` (+ `name`, never blank, written only by migrations or the seed).
  - Tests: `@WebMvcTest` for `web`; `AbstractComponentIT` runs on `RANDOM_PORT`; `StockFixtures.given`
    sets a name.
  - Note "no class-level `@Validated` on controllers" (research R4).

  Gate. Commit `docs(US-04): update CLAUDE.md for the products API`.
- [ ] T022 Run every scenario of `specs/004-products-api/quickstart.md` against ecommerce-infra.
  Clear `CORS_ALLOWED_ORIGINS` from the shell (scenario 10) before running the gate. Record
  the result and the date at the end of `specs/004-products-api/quickstart.md` ("Validation
  2026-MM-DD: scenarios 1–10 passed" or the failures). Mark `specs/004-products-api/spec.md` Status
  `Implemented`. Gate. Commit `chore(US-04): record manual quickstart validation`.

---

## Dependencies & Execution Order

- **T001** blocks everything: every stock insert needs a name.
- **T002, T003, T004** depend on T001 only, and are parallel (different files).
- **T005** (adapter) depends on T004 (port) and T001 (entity name).
- **T006** depends on T004 and T005. The bean wiring needs the adapter (see the deviation note).
- **T007** depends on T006 (same file).
- **T008** depends on T006. **T009** depends on T007 and T008.
- **T010 → T011 → T012** in order (they share `ApiExceptionHandler` and `ProductControllerTest`); they
  depend on T009.
- **T013** can start after T004 (properties only; parallel with Phase 3 and 4). **T014** depends on
  T013 and T012 (same test file).
- **T015** depends on T009 (needs the endpoints); it is parallel with T013 and T014.
- **T016** has no code dependency, but the requested order places it here. **T017** depends on T016,
  T012 and T015 (same `ApplicationHealthIT`).
- **T018** depends on T009. **T019** depends on T018 and T009.
- **T020, T021** depend on all implementation tasks and run in parallel. **T022** comes last.

Story completion: US1 and US2 after T009 (MVP); US3 after T012; US4 after T017 (T003 and T015
included); end-to-end after T019.

## Parallel Examples

```text
After T001:            T002 (migration IT)  |  T003 (local seed)  |  T004 (read model + port)
After T004:            T005 (adapter)       |  T013 (CORS properties)
After T009:            T010→T011→T012 (errors, sequential)  |  T015 (Kafka-down smoke)  |  T018 (component base)
After implementation:  T020 (README)        |  T021 (CLAUDE.md)
```

## Implementation Strategy

1. **MVP** (T001–T009): the named schema, plus list and get with 404. Usable by a frontend from the
   same origin, or server-side.
2. **Hardening** (T010–T012): the full error contract (Story 3).
3. **Operation** (T013–T017): CORS for the browser frontend, proven Kafka independence, and
   OpenAPI docs (Story 4).
4. **End-to-end proof** (T018–T019), then **docs and manual validation** (T020–T022).

Each task is one commit with a green four-step gate, so the branch is releasable after any task.
