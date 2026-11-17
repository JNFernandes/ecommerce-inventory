# ecommerce-inventory

Inventory microservice of the polyglot e-commerce system (Orders: NestJS, Payments: .NET,
Notifications: Python). It owns product stock and per-order stock reservations, and takes part in
the order saga in parallel with Payments ([ADR 0003](docs/adr/0003-inventory-in-order-saga.md)).

Every **state change is event-driven**: every input that changes data is a Kafka event and every
output is a Kafka event. Over HTTP the service offers only the actuator health endpoint and a
**read-only catalog** so that clients can show available stock
([ADR 0007](docs/adr/0007-read-only-catalog-api.md)); no endpoint creates, changes or deletes data.
The rules the code must follow are in the [constitution](.specify/memory/constitution.md).

> Status: **US-01 Reserve stock** ([spec](specs/001-reserve-stock/spec.md)), **US-02 Release
> stock** ([spec](specs/002-release-stock/spec.md)), **US-03 Outbound retry worker**
> ([spec](specs/003-outbound-retry/spec.md)) and **US-04 Read-only products API**
> ([spec](specs/004-products-api/spec.md)) are implemented.

## Events

The contracts (topics, envelope and payload fields) are defined in
[`ecommerce-infra/docs/events.md`](../ecommerce-infra/docs/events.md), the single source of truth
for every service.

| Direction | Topic | Purpose | Status |
|---|---|---|---|
| Consumes | `orders.order-placed` | Reserve stock for every item of the order, all or nothing | implemented |
| Consumes | `orders.order-cancelled` | Release the order's reservation, if there is one | implemented |
| Produces | `inventory.stock-reserved` | All items were reserved | implemented |
| Produces | `inventory.stock-rejected` | At least one item could not be reserved; nothing was reserved | implemented |
| Produces | `inventory.stock-released` | A reservation was released because its order was cancelled | implemented |

`inventory.*` events use the reservation id as `aggregateId` (and Kafka key) and identify the order
by `orderId`. Delivery is at-least-once; consumers deduplicate by `eventId`.

## HTTP API

A **public, read-only catalog** ([ADR 0007](docs/adr/0007-read-only-catalog-api.md),
[contract](specs/004-products-api/contracts/products-api.md)). There is **no authentication**:
anyone who can reach the port can read product names and available quantities. No endpoint creates,
changes or deletes data (write methods answer `405`); adding one needs a new ADR and a constitution
amendment.

| Method and path | Purpose |
|---|---|
| `GET /products?limit=&offset=` | Products in product id order with `availableQuantity`, one page at a time |
| `GET /products/{id}` | One product; `404` if unknown (ids are matched exactly, case-sensitive) |
| `GET /swagger-ui.html` | Swagger UI: every endpoint, parameter, field and status code |
| `GET /v3/api-docs` | The OpenAPI 3 document (also `/v3/api-docs.yaml`) |
| `GET /actuator/health` | Health, readiness and liveness |

> **`availableQuantity` is a snapshot.** It is the stock saved by the reservation and release flows
> (a reserved unit is no longer available), read without locks so it never slows reservations. It
> can change right after you read it: the **authoritative check happens when the order is
> reserved**, so an order for a product shown as available can still be rejected.

```bash
curl -s http://localhost:8081/products
# {"items":[{"id":"SKU-001","name":"Wireless Mouse","availableQuantity":10}, ...],
#  "total":5,"limit":50,"offset":0}

curl -s "http://localhost:8081/products?limit=2&offset=3"
# {"items":[{"id":"SKU-004","name":"27-inch Monitor","availableQuantity":5},
#           {"id":"SKU-005","name":"Webcam HD","availableQuantity":0}],"total":5,"limit":2,"offset":3}

curl -s http://localhost:8081/products/SKU-003
# {"id":"SKU-003","name":"USB-C Hub","availableQuantity":100}

curl -s http://localhost:8081/products/SKU-999
# 404 {"type":"about:blank","title":"Product not found","status":404,
#      "detail":"Product SKU-999 not found.","instance":"/products/SKU-999","productId":"SKU-999"}

curl -s "http://localhost:8081/products?limit=201"
# 400 {"title":"Invalid request parameters","status":400, ...,
#      "errors":[{"parameter":"limit","rejectedValue":"201","message":"must be less than or equal to 200"}]}
```

- `limit`: 1 to 200, default 50. `offset`: 0 or more, default 0. An `offset` past the end returns
  an empty `items` list with the real `total`. Products with 0 available are listed.
- Errors are RFC 9457 problem details (`application/problem+json`). A `limit` or `offset` that is
  out of range, not a whole number, empty or repeated is a `400` listing each invalid parameter in
  `errors`; bad input never causes a `5xx`. A `500` carries only a generic message.
- **CORS**: browsers may call the catalog cross-origin only from the origins in
  `CORS_ALLOWED_ORIGINS` (comma separated, for example `http://localhost:5173`), and only with `GET`.
  Empty (the default) means no cross-origin access; wildcards are refused at startup.
- The catalog needs only PostgreSQL: it keeps working while Kafka is down.

## How a reservation works

1. `orders.order-placed` is parsed strictly and validated. An invalid message goes straight to the
   `consumed_dead_letters` table and its offset is committed.
2. Order lines for the same product are consolidated. The stock rows are locked in ascending
   `productId` order ([ADR 0002](docs/adr/0002-atomic-stock-reservation-strategy.md)).
3. The domain decides, all or nothing: every item is reserved, or the order is rejected with
   reason `insufficient stock`, `unknown product` or `unknown product and insufficient stock` and
   no stock changes. Stock never goes negative (domain check + database `CHECK`).
4. One PostgreSQL transaction saves the stock changes, the reservation, the outcome message and the
   processed event id. After the commit the outcome is published, then the offset is acknowledged
   ([ADR 0004](docs/adr/0004-outcome-stored-with-reservation.md)).
5. A redelivered message (same `eventId`) changes nothing and republishes the stored outcome. A new
   event for an order that already has a reservation is ignored.
6. If publishing fails, the outcome goes to `outbound_dead_letters` (`PENDING`). If processing keeps
   failing, the message is retried with exponential backoff, then stored in `consumed_dead_letters`.

Every transaction about an order first takes a per-order lock, so a placement and a cancellation of
the same order never interleave ([ADR 0005](docs/adr/0005-per-order-serialisation.md)).

## How a release works

1. `orders.order-cancelled` is parsed and validated the same strict way (invalid → dead letter).
2. The order's reservation decides
   ([`Reservation.release`](src/main/java/com/ecommerce/inventory/domain/model/Reservation.java)):
   - **Reserved**: every reserved quantity goes back to its stock item, the reservation becomes
     `RELEASED`, and `inventory.stock-released` is published after the commit.
   - **Rejected** (nothing was ever taken) or **already released**: ignored with a log line; nothing
     changes or is published. Stock is never given back twice.
3. **Cancellation before placement**: if the order has no reservation yet, a row is stored in
   `cancellation_markers`. When the placement arrives later it reserves nothing and publishes nothing,
   so stock is never held for an order that is already cancelled.
4. Idempotency, the write flow and failure handling are the same as for reservations: a redelivered
   cancellation republishes its stored `stock-released` message with the same `eventId`.

## How failed publishes are retried

An outcome that could not be published after the commit is stored in `outbound_dead_letters` as
`PENDING`. A background worker redelivers it ([ADR 0006](docs/adr/0006-outbound-retry-worker.md)):

1. Every `OUTBOUND_RETRY_INTERVAL` (measured from the end of the previous round) a round attempts up
   to `OUTBOUND_RETRY_BATCH_SIZE` due rows (`PENDING`, `next_retry_at <= now`), oldest first.
2. Each row has its own transaction: it is locked with `FOR UPDATE SKIP LOCKED` (other instances skip
   it), the stored topic, key and payload are published unchanged (same `eventId`, at-least-once), and
   the row becomes `RESOLVED` with `resolved_at`.
3. On failure the attempt count grows, the error is stored and the next attempt waits
   `base-delay × multiplier^(attempt−1)`, at most `max-delay`. The round stops at the first failure.
4. After `OUTBOUND_RETRY_MAX_ATTEMPTS` the row becomes `EXHAUSTED`, is logged at error level and is
   never retried automatically.
5. Kafka being down only makes attempts fail; the service stays up and healthy. On shutdown the
   worker finishes the row in progress and leaves the rest untouched.

Logs: one `INFO Outbound retry round: claimed=… resolved=… failed=… exhausted=…` line per round that
claimed rows, one `WARN` per failed attempt (row id, topic, attempt n/max, next attempt time) and one
`ERROR` per exhausted row (row id, event id, topic, key, last error).

Recover an exhausted row manually once the cause is fixed; the next round delivers it:

```sql
update outbound_dead_letters
set status = 'PENDING', next_retry_at = now(), retry_count = 0
where id = '<row id>';
```

## Prerequisites

- JDK 21 (`JAVA_HOME` must point to it; the build fails on older JDKs)
- Docker (for ecommerce-infra and for the Testcontainers-based tests)
- Maven is **not** required: always use the wrapper (`./mvnw`, or `.\mvnw.cmd` on Windows)

## Run locally

1. Start the shared infrastructure from the `ecommerce-infra` repository:

   ```bash
   cd ../ecommerce-infra
   docker compose up -d
   ```

   This provides PostgreSQL on `localhost:5433` (database `inventory`, user `inventory_user` /
   `inventory_pass`) and Kafka on `localhost:9092`, and creates all topics.

2. Run the service (activates the `local` profile, which loads the sample products below):

   ```bash
   ./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
   ```

3. Check it: `curl http://localhost:8081/actuator/health` → `{"status":"UP",...}`, then
   `curl http://localhost:8081/products` for the catalog, or open
   http://localhost:8081/swagger-ui.html.

Flyway applies the migrations in `src/main/resources/db/migration` at startup. The service starts
and reports healthy even when Kafka is down: listener containers are started in the background
once the application is ready and keep retrying the connection.

### Sample products (local profile only)

`./mvnw spring-boot:run` seeds these products through `db/local/R__seed_local_stock.sql`. Existing
rows are never reset or duplicated: the seed only (re)sets their names, never their quantities.
Tests, the Docker image and production never load them.

| Product id | Name | Available (first seed) |
|---|---|---|
| `SKU-001` | Wireless Mouse | 10 |
| `SKU-002` | Mechanical Keyboard | 25 |
| `SKU-003` | USB-C Hub | 100 |
| `SKU-004` | 27-inch Monitor | 5 |
| `SKU-005` | Webcam HD | 0 |

Products that existed before names were introduced (`V5__product_name.sql`) are named after their
product id until something gives them a real name.

### Try it

Place an order through the Orders service (for example from Postman) using these product ids, or
produce an `orders.order-placed` message yourself in kafka-ui (http://localhost:8080 → Topics →
`orders.order-placed` → Produce Message), with the order id as key:

```json
{"eventId":"8f0c6a1e-6a4b-4a43-9a1c-1f5f7f0a2b10","occurredAt":"2026-11-13T10:15:30.123Z",
 "aggregateId":"3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","version":1,
 "customerId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
 "items":[{"productId":"SKU-003","quantity":2,"unitPrice":19.99}],"totalAmount":39.98}
```

Use new ids for every order. Expected:

- **Reserved** (for example `SKU-003`): the log shows `Order … handled: reservation … RESERVED`,
  `inventory.stock-reserved` gets a message, and the stock decreases.
- **Rejected** (for example `SKU-005`, or an unknown `SKU-999`): `inventory.stock-rejected` gets a
  message with the reason and the failing items; no stock changes.

To cancel the order, produce on `orders.order-cancelled` with the same order id as key and
`aggregateId` (any new `eventId`):

```json
{"eventId":"2c9d8e7f-6a5b-4c3d-9e8f-7a6b5c4d3e2f","occurredAt":"2026-11-13T10:20:00.000Z",
 "aggregateId":"3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","version":1,"reason":"payment failed"}
```

Expected: `inventory.stock-released` gets a message with the reserved items, the stock goes back up
and the reservation is `RELEASED`. Cancelling an order Inventory has never seen stores a cancellation
marker; placing that order afterwards reserves nothing.

More scenarios: [US-01 quickstart](specs/001-reserve-stock/quickstart.md) (redelivery, duplicate
order, invalid message, reset commands), [US-02 quickstart](specs/002-release-stock/quickstart.md) and
[US-03 quickstart](specs/003-outbound-retry/quickstart.md) (Kafka stopped and started).

### Configuration

Every setting comes from an environment variable; the defaults match ecommerce-infra.

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/inventory` | JDBC URL of the Inventory database |
| `DB_USERNAME` | `inventory_user` | Database user |
| `DB_PASSWORD` | `inventory_pass` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `KAFKA_CONSUMER_GROUP_ID` | `inventory-service` | Consumer group id |
| `KAFKA_PRODUCER_MAX_BLOCK_MS` | `10000` | Max time a send waits for broker metadata |
| `KAFKA_TOPIC_ORDER_PLACED` | `orders.order-placed` | Consumed topic |
| `KAFKA_TOPIC_STOCK_RESERVED` | `inventory.stock-reserved` | Produced topic |
| `KAFKA_TOPIC_STOCK_REJECTED` | `inventory.stock-rejected` | Produced topic |
| `KAFKA_TOPIC_ORDER_CANCELLED` | `orders.order-cancelled` | Consumed topic |
| `KAFKA_TOPIC_STOCK_RELEASED` | `inventory.stock-released` | Produced topic |
| `OUTBOUND_RETRY_ENABLED` | `true` | Run the outbound retry worker |
| `OUTBOUND_RETRY_INTERVAL` | `30s` | Pause between the end of one retry round and the start of the next |
| `OUTBOUND_RETRY_BATCH_SIZE` | `50` | Maximum rows attempted per round (≥ 1) |
| `OUTBOUND_RETRY_MAX_ATTEMPTS` | `10` | Worker attempts before a row becomes `EXHAUSTED` (≥ 1) |
| `OUTBOUND_RETRY_BASE_DELAY` | `30s` | Wait after the first failed attempt |
| `OUTBOUND_RETRY_MULTIPLIER` | `2.0` | Factor applied to the wait after each further failure (≥ 1.0) |
| `OUTBOUND_RETRY_MAX_DELAY` | `1h` | Longest wait between attempts (≥ base delay) |
| `SERVER_PORT` | `8081` (`8080` in the Docker image) | HTTP port (catalog, docs, health); 8080 is used by kafka-ui locally |
| `CORS_ALLOWED_ORIGINS` | empty | Comma-separated origins allowed to call the catalog from a browser (e.g. `http://localhost:5173`); empty means no cross-origin access; wildcards stop the service at startup |

Tuning without environment variables (`application.yml`, `inventory.*`): inbound retries
(`consumer-retry`: 3 retries, 1 s → 10 s), publish timeout (15 s), first outbound retry delay (30 s)
and lock timeout (5 s, for stock rows and the per-order lock).

### Database tables

| Table | Holds |
|---|---|
| `stock_items` | Name and available quantity per product (quantity never negative, name never blank); the catalog reads it without locks |
| `reservations`, `reservation_items` | One reservation per order: status (`RESERVED`, `REJECTED`, `RELEASED`), consolidated items, failing items when rejected, `released_at` |
| `cancellation_markers` | Orders cancelled before Inventory saw their placement (the later placement reserves nothing) |
| `outcome_messages` | The exact messages published for each reservation (reserve and release), for republishing |
| `processed_events` | Inbound event ids already handled (idempotency for both topics), linked to the message they produced |
| `outbound_dead_letters` | Outcomes that could not be published: `PENDING` (retried by the worker), `RESOLVED` (delivered, `resolved_at` set) or `EXHAUSTED` (gave up, kept for inspection) |
| `consumed_dead_letters` | Inbound messages that could not be processed, stored as received |

### Docker

```bash
docker build -t ecommerce-inventory .
docker run --rm -p 8081:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5433/inventory \
  ecommerce-inventory
```

The image runs as a non-root user and has a `HEALTHCHECK` on `/actuator/health`. Kafka advertises
`localhost:9092` to host clients, so a container that needs Kafka must join the ecommerce-infra
compose network and use `KAFKA_BOOTSTRAP_SERVERS=kafka:29092`.

The ecommerce-infra compose (`inventory` service, profile `apps`) already publishes container port
`8080` on host port `8081` (kafka-ui keeps 8080). To let a browser frontend call the catalog, add
`CORS_ALLOWED_ORIGINS` with the frontend origin (for example `http://localhost:5173`) to that
service's `environment`.

## Quality gate

Run after every task, in this order (constitution Principle X). All four must pass before a commit.

```bash
./mvnw -B -q compile              # javac with -Xlint:all -Werror
./mvnw -B -q checkstyle:check     # zero violations, warnings included
./mvnw -B -q spotless:check       # formatting (fix with ./mvnw spotless:apply)
./mvnw -B verify                  # unit + integration + component tests and the JaCoCo gate
```

On Windows use `.\mvnw.cmd` with the same arguments. `verify` needs Docker running
(Testcontainers starts PostgreSQL and Kafka).

- **Unit tests** (`*Test`, Surefire): domain and application in isolation.
- **Integration tests** (`*IT`, Failsafe): persistence adapters, migrations, constraints and
  concurrency on a real PostgreSQL.
- **Web tests** (`web/*Test`, `@WebMvcTest`): the HTTP contract of the catalog through MockMvc.
- **Component tests** (`component/*IT`): the whole service on real PostgreSQL and Kafka, on a real
  HTTP port; other services are simulated by producing their events.
- **Coverage**: JaCoCo requires at least 80% line coverage in every package under `domain` and
  `application`. The report is in `target/site/jacoco/index.html`.

## Project layout

```text
src/main/java/com/ecommerce/inventory/
├── InventoryApplication.java   Spring Boot entry point
├── messaging/                  OrderPlacedListener, OrderCancelledListener, StrictJsonParser, inbound DTOs
├── web/                        ProductController (read-only catalog), ApiExceptionHandler (problem
│                               details), QueryParameterGuard, response records in web/dto
├── application/                ReserveStockService, ReleaseStockService, OutcomePublisher,
│                               OutboundRetryService + OutboundRetryPolicy, ProductQueryService +
│                               ProductView/ProductPage, ports (OrderLock, ProductCatalogReader, …)
├── domain/                     StockItem and Reservation aggregates, value objects, domain events;
│                               plain Java, no Spring/JPA/Kafka
└── infrastructure/
    ├── config/                 Typed inventory.* properties, clock, policies, CORS, OpenAPI
    ├── persistence/            JPA entities, adapters for the domain and application ports
    ├── scheduling/             OutboundRetryScheduler (retry worker schedule and shutdown)
    └── kafka/                  Publisher, message factory and JSON shapes, error handler,
                                dead-letter recoverer, background listener start
src/main/resources/
├── application.yml             Configuration (environment variables with local defaults)
├── application-local.yml       Local profile: adds the sample products
├── db/migration/               Flyway migrations (V1 baseline, V2 reserve, V3 release, V4 retry,
│                               V5 product names)
└── db/local/                   Local-only seed (R__seed_local_stock.sql)
src/test/java/                  *Test (unit) and *IT (integration/component) tests
docs/adr/                       Architecture Decision Records
specs/                          Feature specifications, plans and tasks (Spec Kit)
checkstyle.xml                  Lint rules
Dockerfile                      Multi-stage image build
```

## Documentation

- [Constitution](.specify/memory/constitution.md): the binding rules
- [Architecture Decision Records](docs/adr/README.md)
- [Event contracts](../ecommerce-infra/docs/events.md) (ecommerce-infra)
- [CLAUDE.md](CLAUDE.md): working conventions for Claude Code
