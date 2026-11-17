# 0007. Read-only catalog API for available stock

- **Status**: Accepted
- **Date**: 2026-11-17

## Context

Inventory is event-driven only (constitution 1.0.x, Principle II): every input and output is a
Kafka event, and the only HTTP surface is Actuator health. Inventory is also the only service that
knows how much of each product is available (`stock_items`).

Clients (the storefront frontend, and anyone testing the system by hand) need to show which
products exist and how many units are available before an order is placed. Today they cannot: the
product ids and quantities are only visible in the Inventory database or in the local seed script.
Placing an order for a product that is out of stock is only discovered afterwards, through
`inventory.stock-rejected`.

Constraints:

- Stock changes MUST keep going through the reservation flow (Kafka in, one transaction, Kafka out).
  An HTTP path that changes stock would bypass idempotency, the per-order lock (ADR 0005) and the
  outcome events other services rely on.
- Reading stock MUST NOT slow down or block reservations, which lock stock rows with
  `PESSIMISTIC_WRITE` (ADR 0002).
- Port `8080` is used by kafka-ui in ecommerce-infra.

## Decision

We will expose a **read-only catalog API** and nothing else:

- `GET /products` — every product with its `availableQuantity`.
- `GET /products/{id}` — one product with its `availableQuantity`; `404` when it is unknown.

Rules (constitution 1.1.0, Principle II):

1. No endpoint creates, changes or deletes data. All business state changes still arrive as Kafka
   events and all output still leaves as Kafka events.
2. A controller handles I/O only and calls exactly one application **query** service method.
   Response DTOs are immutable records, separate from domain and JPA classes.
3. The query reads `stock_items` without locks. The value is a snapshot: it may change a moment
   later, and the reservation remains the only authoritative check.
4. CORS allowed origins come only from `CORS_ALLOWED_ORIGINS`, empty by default.
5. The HTTP port defaults to `8081` (`SERVER_PORT` overrides it; the Docker image uses `8080`).
6. The endpoints are documented with OpenAPI (springdoc) or in `README.md`, and tested with MockMvc
   or a real HTTP call in the component tests.
7. Any write endpoint needs a new accepted ADR and a further constitution amendment.

**Note (2026-11-17, constitution 1.1.1):** the documentation is generated with springdoc and served
read-only at `GET /v3/api-docs` (OpenAPI document) and `GET /swagger-ui.html` (viewer) in every
environment, so the frontend developer can browse the contract. These endpoints expose no data and
are part of the permitted HTTP surface.

### Alternatives considered

- **Replicated catalog in another service** (for example Orders or a catalog service keeps a copy of
  the stock, fed by Inventory events). Rejected for now: Inventory does not publish stock-level
  events, so this needs new topics and contracts in `ecommerce-infra/docs/events.md`, a projection
  in another service and its own eventual-consistency handling, only to show a number that
  Inventory already holds. It remains the path to take if read traffic or coupling becomes a
  problem.
- **Static product list in the frontend.** Rejected: it cannot show available quantities, goes
  stale as soon as stock changes or products are added, and duplicates product ids outside the
  service that owns them.

## Consequences

- Clients can show available stock and avoid placing orders that are certain to be rejected.
- The service gains an HTTP layer (Spring Web controllers, a query service, CORS configuration,
  endpoint documentation and API tests) that must stay read-only; reviewers reject any write
  endpoint.
- Clients couple to Inventory over HTTP for reads. Inventory being down hides the catalog, but
  never affects order processing, which stays on Kafka.
- Displayed quantities can be stale by the time an order is placed; the reservation flow still
  decides, and a rejection is still a normal outcome.
- Lock-free reads add load to `stock_items` without contending with reservations.
- Implementing the endpoints is a separate user story (spec, plan, tasks).
