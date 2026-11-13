# ecommerce-inventory

Inventory microservice of the polyglot e-commerce system (Orders: NestJS, Payments: .NET,
Notifications: Python). It owns product stock and per-order stock reservations, and takes part in
the order saga in parallel with Payments ([ADR 0003](docs/adr/0003-inventory-in-order-saga.md)).

The service is **event-driven only**: every input is a Kafka event and every output is a Kafka
event. It exposes no business HTTP API; the only HTTP surface is the actuator health endpoint.
The rules the code must follow are in the [constitution](.specify/memory/constitution.md).

> Status: project skeleton. No user story is implemented yet: there are no listeners, no domain
> model and no tables beyond the Flyway baseline.

## Events

The contracts (topics, envelope and payload fields) are defined in
[`ecommerce-infra/docs/events.md`](../ecommerce-infra/docs/events.md), the single source of truth
for every service.

| Direction | Topic | Purpose |
|---|---|---|
| Consumes | `orders.order-placed` | Reserve stock for every item of the order, all or nothing |
| Consumes | `orders.order-cancelled` | Release the order's reservation, if there is one |
| Produces | `inventory.stock-reserved` | All items were reserved |
| Produces | `inventory.stock-rejected` | At least one item could not be reserved; nothing was reserved |
| Produces | `inventory.stock-released` | A reservation was released because its order was cancelled |

`inventory.*` events use the reservation id as `aggregateId` (and Kafka key) and identify the order
by `orderId`. Delivery is at-least-once; consumers deduplicate by `eventId`.

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

2. Run the service:

   ```bash
   ./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
   ```

3. Check it: `curl http://localhost:8081/actuator/health` → `{"status":"UP",...}`.

Flyway applies the migrations in `src/main/resources/db/migration` at startup. The service starts
and reports healthy even when Kafka is down: listener containers are started in the background
once the application is ready and keep retrying the connection.

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
| `SERVER_PORT` | `8081` (`8080` in the Docker image) | HTTP port of the health endpoint; 8080 is used by kafka-ui locally |

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

## Quality gate

Run after every task, in this order (constitution Principle X). All four must pass before a commit.

```bash
./mvnw -B -q compile              # javac with -Xlint:all -Werror
./mvnw -B -q checkstyle:check     # zero violations, warnings included
./mvnw -B -q spotless:check       # formatting (fix with ./mvnw spotless:apply)
./mvnw -B verify                  # unit + integration + component tests and the JaCoCo gate
```

On Windows use `.\mvnw.cmd` with the same arguments. `verify` needs Docker running
(Testcontainers starts PostgreSQL and, for component tests, Kafka).

- **Unit tests** (`*Test`, Surefire): domain and application in isolation.
- **Integration and component tests** (`*IT`, Failsafe): real PostgreSQL / Kafka via
  Testcontainers.
- **Coverage**: JaCoCo requires at least 80% line coverage in every package under `domain` and
  `application`. The report is in `target/site/jacoco/index.html`.

## Project layout

```text
src/main/java/com/ecommerce/inventory/
├── InventoryApplication.java   Spring Boot entry point
├── messaging/                  Kafka listeners: deserialize, validate, call one application service
├── application/                Use-case orchestration and transactions; no business rules
├── domain/                     Aggregates (StockItem, Reservation), value objects, domain events;
│                               plain Java, no Spring/JPA/Kafka
└── infrastructure/             JPA persistence, Kafka producer and config, dead-letter retry
    └── kafka/                  Background start of listener containers
src/main/resources/
├── application.yml             Configuration (environment variables with local defaults)
└── db/migration/               Flyway migrations (V1__baseline.sql)
src/test/java/                  *Test (unit) and *IT (integration/component) tests
docs/adr/                       Architecture Decision Records
checkstyle.xml                  Lint rules
Dockerfile                      Multi-stage image build
```

## Documentation

- [Constitution](.specify/memory/constitution.md): the binding rules
- [Architecture Decision Records](docs/adr/README.md)
- [Event contracts](../ecommerce-infra/docs/events.md) (ecommerce-infra)
- [CLAUDE.md](CLAUDE.md): working conventions for Claude Code
