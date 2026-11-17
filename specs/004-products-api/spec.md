# Feature Specification: Read-Only Products API with Available Stock (US-04)

**Feature Branch**: `feature/US-04-products-api`

**Created**: 2026-11-17

**Status**: Implemented

**Input**: User description: "US-04: Read-only products API with available stock. Inventory exposes
a read-only catalog so that clients (the future web frontend) can show products and how many units
are available. GET /products lists all products with id (productId), name and availableQuantity,
ordered by productId, with optional pagination (limit default 50, maximum 200, and offset); the
response is an object with items, total, limit and offset. GET /products/{id} returns one product,
or 404 with a clear error body. availableQuantity is the current stock after reservations, read
without locks, a snapshot; the authoritative check still happens when the order is reserved.
Products get a non-null name (migration, backfilled from the productId); the local sample products
get realistic names, documented in the README. Invalid limit or offset returns 400 with validation
details, never a 500. Response DTOs separate from domain and persistence; controller → query
service → repository. Endpoints documented. CORS from CORS_ALLOWED_ORIGINS (comma separated, empty
by default); port from SERVER_PORT, default 8081. Works when Kafka is unreachable. No
authentication; the README states the catalog is public. Out of scope: write endpoints, creating or
editing products, search, categories, prices."

## Context

Inventory owns the stock of every product and decides reservations from Kafka events (US-01 to
US-03). Today nobody outside the service can see which products exist or how many units are left:
a client only learns that a product is out of stock when its order is rejected.

Constitution 1.1.0 (Principle II) and ADR 0007 allow exactly one HTTP addition: a **read-only
catalog** with two endpoints, `GET /products` and `GET /products/{id}`. This feature implements it.
The paths, query parameters, status codes and field names below are the agreed external contract
with clients, so the spec states them; how they are built belongs in the plan.

The catalog never changes data. Stock still changes only through the reservation and release flows.

## Clarifications

### Session 2026-11-17

- Q: How do products get names? → A: Existing rows get name = product id; the local seed sets real
  names: SKU-001 "Wireless Mouse", SKU-002 "Mechanical Keyboard", SKU-003 "USB-C Hub", SKU-004
  "27-inch Monitor", SKU-005 "Webcam HD" (stock 0).
- Q: What does bad input return? → A: `400` with Spring's standard problem-detail body (RFC 9457).
- Q: Ordering and `total`? → A: Product id ascending; `total` is a count of all products.
- Q: Caching? → A: None.
- Q: How are the endpoints documented? → A: springdoc OpenAPI, with the Swagger UI at
  `/swagger-ui.html` for the frontend developer.
- Q: What stock is shown? → A: The raw available quantity snapshot only; no reserved or pending
  breakdown.
- Q: Writes, prices, authentication? → A: Out of scope.
- Q: Constitution Principle II lists the HTTP surface exactly; where are the API docs served? → A:
  `/swagger-ui.html` and `/v3/api-docs` in every environment, added to Principle II by a PATCH
  amendment (1.1.1) and noted in ADR 0007.

## User Scenarios & Testing *(mandatory)*

The "users" are client applications (the future web frontend, and people trying the system by hand)
and the operators who run Inventory.

### User Story 1 - Browse the catalog with available stock (Priority: P1)

A client asks for the list of products and gets each product's id, name and how many units are
available right now, in a stable order, one page at a time.

**Why this priority**: This is the main value: a storefront can show what can be ordered and avoid
offering products that are out of stock.

**Independent Test**: With a few products in stock, request the list; verify every product appears
once with its id, name and available quantity, ordered by product id, together with the total, the
limit and the offset that were applied.

**Acceptance Scenarios**:

1. **Given** products `SKU-001` (10 available), `SKU-002` (25) and `SKU-005` (0), **When** a client
   requests `GET /products` without parameters, **Then** the response is `200` with `items` holding
   the three products in ascending product id order, each with `id`, `name` and
   `availableQuantity`, and with `total` 3, `limit` 50 and `offset` 0.
2. **Given** 120 products, **When** a client requests `GET /products?limit=50&offset=100`, **Then**
   `items` holds the products at positions 101 to 120 in product id order (20 items), `total` is
   120, `limit` 50 and `offset` 100.
3. **Given** 5 products, **When** a client requests `GET /products?offset=10`, **Then** the response
   is `200` with an empty `items` list, `total` 5, `limit` 50 and `offset` 10.
4. **Given** no products at all, **When** a client requests `GET /products`, **Then** the response is
   `200` with an empty `items` list and `total` 0.
5. **Given** a product with 0 units available, **When** the list is requested, **Then** the product
   is still listed, with `availableQuantity` 0 (out-of-stock products are not hidden).
6. **Given** an order reserved 2 of the 10 units of `SKU-001`, **When** the list is requested after
   the reservation was saved, **Then** `SKU-001` shows `availableQuantity` 8; after the order is
   cancelled and the reservation released, it shows 10 again.

---

### User Story 2 - Look up one product (Priority: P1)

A client asks for a single product by its id and gets its id, name and available quantity, or a
clear "not found" answer when Inventory does not know the product.

**Why this priority**: A product page or a cart needs the current availability of one specific
product; it is as basic as the list.

**Independent Test**: Request an existing product and an unknown one; verify the first returns the
product and the second returns `404` with a body that names the missing id.

**Acceptance Scenarios**:

1. **Given** product `SKU-003` with 100 units available, **When** a client requests
   `GET /products/SKU-003`, **Then** the response is `200` with `id` `SKU-003`, its `name` and
   `availableQuantity` 100.
2. **Given** no product `SKU-999`, **When** a client requests `GET /products/SKU-999`, **Then** the
   response is `404` with an error body stating that product `SKU-999` was not found.
3. **Given** product `SKU-003`, **When** a client requests `GET /products/sku-003`, **Then** the
   response is `404` (product ids are matched exactly, including case).

---

### User Story 3 - Get clear answers to bad requests (Priority: P2)

A client that sends invalid paging parameters gets a `400` that says which parameter is wrong and
why, never a server error, so the client developer can fix the call.

**Why this priority**: Needed for a usable contract and to keep server errors meaningful, but it does
not add catalog data.

**Independent Test**: Send each kind of invalid paging value and verify each returns `400` with a
detail naming the parameter, the rejected value and the allowed range.

**Acceptance Scenarios**:

1. **Given** any catalog, **When** a client requests `GET /products?limit=0`, `limit=201` or
   `limit=-5`, **Then** the response is `400` with a validation detail for `limit` stating it must be
   between 1 and 200.
2. **Given** any catalog, **When** a client requests `GET /products?offset=-1`, **Then** the response
   is `400` with a validation detail for `offset` stating it must be 0 or greater.
3. **Given** any catalog, **When** a client requests `GET /products?limit=abc` or `offset=1.5`,
   **Then** the response is `400` with a validation detail stating the parameter must be a whole
   number.
4. **Given** both `limit` and `offset` are invalid, **When** the list is requested, **Then** the
   `400` body lists a detail for each of them.
5. **Given** any catalog, **When** a client sends `POST`, `PUT`, `PATCH` or `DELETE` to `/products`
   or `/products/{id}`, **Then** the request is refused with `405` and nothing changes.

---

### User Story 4 - Call the catalog from a browser app and run it alongside the shared infrastructure (Priority: P2)

Operators decide which web origins may call the catalog from a browser, choose the HTTP port, and
can rely on the catalog staying available when the message broker is down. Client developers find
every endpoint and field documented, and the sample products have realistic names to build against.

**Why this priority**: Required for the future web frontend and for local development, but the
catalog already delivers value to server-side clients without it.

**Independent Test**: Start the service with one allowed origin; verify a browser request from that
origin is allowed and one from another origin is not; stop the broker and verify the catalog still
answers; start locally and verify the five sample products have realistic names and are listed in
the README.

**Acceptance Scenarios**:

1. **Given** `CORS_ALLOWED_ORIGINS=http://localhost:5173`, **When** a browser app at
   `http://localhost:5173` calls `GET /products`, **Then** the cross-origin request is allowed.
2. **Given** the same setting, **When** a browser app at `http://evil.example` calls the catalog,
   **Then** the cross-origin request is not allowed.
3. **Given** `CORS_ALLOWED_ORIGINS` is not set, **When** any browser app on another origin calls the
   catalog, **Then** no cross-origin request is allowed (server-to-server calls still work).
4. **Given** `CORS_ALLOWED_ORIGINS=http://localhost:5173, https://shop.example`, **When** the service
   starts, **Then** both origins are allowed (comma separated, surrounding spaces ignored).
5. **Given** the message broker is unreachable, **When** a client calls either endpoint, **Then** it
   answers normally from the database.
6. **Given** `SERVER_PORT` is not set, **When** the service runs locally, **Then** the catalog
   answers on port 8081; with `SERVER_PORT=9090` it answers on 9090.
7. **Given** the local profile, **When** the service starts, **Then** `SKU-001` to `SKU-005` have
   realistic product names, also for a local database where those products already existed without
   a name; their available quantities are not reset.

---

### Edge Cases

- **Stock changes while a client pages through the list**: each response reflects the stock at the
  moment it was read; consecutive pages are not a consistent snapshot, and `total` may differ between
  pages if products were added in between. Clients treat quantities as indicative.
- **A reservation is being decided while the catalog is read**: the read neither waits for nor blocks
  the reservation; it shows the quantity from before or after that reservation, never a partial one.
- **A product shows as available but its order is rejected** (someone else reserved the last units in
  between): expected; the reservation is the authoritative check, and the README says so.
- **A product id containing URL-reserved characters** requested in encoded form (for example a
  space as `%20`): it is looked up exactly as decoded; if no such product exists the answer is `404`.
  An encoded slash (`%2F`) is refused by the HTTP server itself with `400` before reaching the
  application (its body is the server's default error body, not a problem detail); never a `5xx`.
- **A very long product id**: `404` (no product can have it), not a `400` or `500`.
- **Unknown query parameters** on `GET /products`: ignored.
- **`limit` or `offset` given more than once, or empty** (`?limit=`): `400` with a validation detail,
  never a `500`.
- **The database is unreachable**: the catalog answers with a server error that exposes no internal
  details (no stack trace, SQL or connection string), and the health check reports the problem.
- **Existing products created before this feature** (no name): they get their product id as name, so
  every product always has a name.
- **Paths outside the catalog** (for example `/products/SKU-001/reservations`): `404`.

## Requirements *(mandatory)*

### Functional Requirements

**Listing products**

- **FR-001**: The system MUST answer `GET /products` with `200` and an object holding `items`,
  `total`, `limit` and `offset`.
- **FR-002**: Each item MUST hold exactly `id` (the product id), `name` and `availableQuantity` (a
  whole number, 0 or greater).
- **FR-003**: Items MUST be ordered by product id ascending, using a simple, deterministic character
  order that does not depend on the server's language settings, so paging is stable.
- **FR-004**: `limit` MUST be optional, default 50, and accept whole numbers from 1 to 200 inclusive.
  `offset` MUST be optional, default 0, and accept whole numbers 0 or greater.
- **FR-005**: The response MUST contain at most `limit` items, starting after the first `offset`
  products in order; an `offset` at or beyond the total MUST return an empty `items` list with `200`.
- **FR-006**: `total` MUST be the number of products in the catalog at the time of the request;
  `limit` and `offset` MUST echo the values applied (defaults included).
- **FR-007**: Products with 0 units available MUST be listed.

**Getting one product**

- **FR-008**: The system MUST answer `GET /products/{id}` with `200` and the product (`id`, `name`,
  `availableQuantity`) when a product with exactly that id exists (case-sensitive).
- **FR-009**: When no product has that id, the system MUST answer `404` with an error body that
  states the product was not found and includes the requested id.

**Errors**

- **FR-010**: A `limit` or `offset` that is out of range, not a whole number, empty or repeated MUST
  be answered with `400` and a body holding one validation detail per invalid parameter (parameter
  name, rejected value, and the rule it broke).
- **FR-011**: Invalid client input MUST never produce a `5xx` response.
- **FR-012**: All error responses produced by the application (`400`, `404`, `405`, `5xx`) MUST use
  Spring's standard
  problem-detail body (RFC 9457: `type`, `title`, `status`, `detail`, `instance`). For `400`, the
  per-parameter validation details of FR-010 are an additional `errors` property of that body.
  Error bodies MUST NOT expose stack traces, SQL, class names or connection details.

**Read-only behaviour (constitution Principle II)**

- **FR-013**: The catalog MUST NOT create, change or delete any data. Requests with any method other
  than `GET` (and `HEAD`/`OPTIONS` as required by the HTTP and CORS rules) to the catalog paths MUST
  be answered with `405` and change nothing.
- **FR-014**: `availableQuantity` MUST be the product's current available stock as saved by the
  reservation and release flows: units held by a reservation are not available; released units are
  available again. It is the raw stored quantity; the catalog shows no reserved, pending or other
  breakdown.
- **FR-015**: Reading the catalog MUST NOT lock stock and MUST NOT wait for or delay reservations or
  releases in progress. A response reflects saved stock only, never a partially applied decision.
- **FR-016**: The only HTTP endpoints of the service MUST be the health endpoints, the two catalog
  endpoints and the read-only API documentation (`/v3/api-docs` with `/v3/api-docs.yaml` and
  `/v3/api-docs/swagger-config`, and `/swagger-ui.html` with the viewer's static resources), in
  every environment. The server's internal error path (`/error`) is not an endpoint (constitution
  1.1.2). A test MUST prove that no other endpoint is mapped.

**Product names**

- **FR-017**: Every product MUST have a non-empty name, stored with its stock.
- **FR-018**: Products that exist before this feature MUST receive their product id as name, in the
  same schema change that introduces names, so no product is ever without one.
- **FR-019**: The local sample products MUST get these names: `SKU-001` "Wireless Mouse", `SKU-002`
  "Mechanical Keyboard", `SKU-003` "USB-C Hub", `SKU-004` "27-inch Monitor", `SKU-005` "Webcam HD"
  (stock 0). Starting locally MUST set these names also on a local database where the products
  already exist, without changing their available quantities.
- **FR-020**: Product names MUST NOT be changeable through the catalog (no product creation or
  editing in this feature).

**Operation**

- **FR-021**: The catalog MUST work while the message broker is unreachable: it depends only on the
  database. Calling it MUST NOT trigger any broker connection.
- **FR-022**: Both endpoints, their parameters, every response field, the error body and every status
  code MUST be documented as generated OpenAPI documentation, served at `/v3/api-docs` with an
  interactive viewer at `/swagger-ui.html`, available like the catalog without authentication and
  without Kafka; the README points to both.
- **FR-023**: Cross-origin browser access MUST be allowed only for the origins listed in
  `CORS_ALLOWED_ORIGINS` (comma separated, surrounding spaces ignored), and only for reading. Empty or
  unset MUST allow no cross-origin access. Wildcard origins MUST NOT be accepted.
- **FR-024**: The HTTP port MUST come from `SERVER_PORT`, default `8081` (the container image keeps
  its documented port).
- **FR-025**: The catalog MUST be public: no authentication or authorization in this feature. The
  README MUST state that the catalog is public and unauthenticated.
- **FR-026**: The README MUST state that `availableQuantity` is a snapshot and that the authoritative
  availability check happens when the order is reserved, and MUST list the sample products with their
  names and quantities.

### Key Entities *(include if feature involves data)*

- **Product (catalog view of a stock item)**: what a client sees. Id (the product id used in orders),
  name, available quantity. One per stock item; read-only.
- **Product page**: a slice of the ordered catalog: the items, the total number of products, and the
  limit and offset that produced it.
- **Error response**: status, title, human-readable detail; for validation failures, a list of
  per-parameter details (parameter, rejected value, rule).
- **Stock item** (existing): gains a name. Its available quantity keeps being changed only by
  reservations and releases.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A client can list every product with its name and available quantity in a single
  request when the catalog holds up to 200 products.
- **SC-002**: With 10,000 products, a page of 200 products and a single-product lookup each answer in
  under 500 ms on a developer machine.
- **SC-003**: A reserved unit disappears from `availableQuantity` as soon as the reservation is
  saved, and a released unit reappears as soon as the release is saved: 100% of reads made after the
  save show the new quantity.
- **SC-004**: Reading the catalog continuously while orders are being reserved never delays a
  reservation and never causes a reservation to fail.
- **SC-005**: 100% of the invalid-input cases listed in User Story 3 and the edge cases return a
  `4xx` answer with a detail; none returns a `5xx`.
- **SC-006**: With the message broker stopped, 100% of catalog requests succeed.
- **SC-007**: A frontend developer can build a product listing against the documentation alone,
  without reading the source code.
- **SC-008**: No request to the catalog, of any method, changes any stored data.

## Assumptions

- **Catalog = stock items**: every row of the stock store is a product of the catalog; there is no
  separate product store and no "inactive" products. Unknown products only appear in rejected orders
  and are not part of the catalog.
- **Name rules**: names are non-empty text of up to 200 characters. They are maintained in the
  database (migrations, local seed, or manual SQL) since creating or editing products is out of
  scope.
- **Ordering**: "ordered by productId" means plain character order (so `SKU-010` comes after
  `SKU-002`, and uppercase letters before lowercase), independent of database locale.
- **Paging**: offset/limit paging is sufficient at the expected catalog size; no cursors and no
  `Link` headers. `total` counts all products, not only the current page.
- **Response caching**: none. Responses always read current stock; no cache headers beyond
  defaults.
- **HEAD/OPTIONS**: allowed where the HTTP framework and CORS preflight require them; they change
  nothing.
- **Container port**: the Docker image keeps listening on 8080 (set through `SERVER_PORT` in the
  image), as documented today.
- **Dependencies**: the shared infrastructure (ecommerce-infra) provides the database; no event
  contract changes are needed, so `ecommerce-infra/docs/events.md` is unchanged.
- **Out of scope**: any write endpoint, creating or editing products, search, filtering, sorting
  options, categories, prices, images, authentication, rate limiting.
