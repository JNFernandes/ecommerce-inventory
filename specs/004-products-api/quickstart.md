# Quickstart: Read-Only Products API (US-04)

Manual validation against ecommerce-infra. Contract: [contracts/products-api.md](contracts/products-api.md).

## Prerequisites

- JDK 21 (`JAVA_HOME`), Docker running.
- `cd ../ecommerce-infra && docker compose up -d` (PostgreSQL on 5433, Kafka on 9092).

## Automated checks

```powershell
.\mvnw.cmd -B -q compile
.\mvnw.cmd -B -q checkstyle:check
.\mvnw.cmd -B -q spotless:check
.\mvnw.cmd -B verify
```

Expected: all pass. Among them are `ProductQueryServiceTest`, `ProductControllerTest`,
`ProductCatalogReaderAdapterIT`, `ProductNameMigrationIT`, `LocalSeedIT`, `ApplicationHealthIT` and
`ProductCatalogComponentIT`.

## Run

```powershell
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:5173'
.\mvnw.cmd spring-boot:run
```

## Scenarios

1. **List**: `curl -s http://localhost:8081/products`. Expect `200` with the five sample products,
   ordered `SKU-001`..`SKU-005`, with names (Wireless Mouse … Webcam HD), `total` 5, `limit` 50,
   `offset` 0. `SKU-005` is listed with `availableQuantity` 0.
2. **Paging**: `curl -s "http://localhost:8081/products?limit=2&offset=3"`. Expect `SKU-004` and
   `SKU-005`, `total` 5. With `offset=10`, `items` is empty.
3. **One product**: `curl -s http://localhost:8081/products/SKU-003` returns `200`.
   `curl -si http://localhost:8081/products/SKU-999` returns `404` with `application/problem+json`
   and `"productId":"SKU-999"`. `/products/sku-003` also returns `404`.
4. **Bad input**: `curl -si "http://localhost:8081/products?limit=0"`, `?limit=201`, `?offset=-1`,
   `?limit=abc`, `?limit=` and `?limit=1&limit=2` each return `400` with an `errors` entry naming
   the parameter. None returns `500`.
5. **Read-only**: `curl -si -X POST http://localhost:8081/products` returns `405` with an
   `Allow` header. The same holds for `PUT`, `PATCH` and `DELETE`, and nothing changes.
6. **Stock follows reservations**: produce an `orders.order-placed` for 2 × `SKU-003` (README "Try
   it"). `GET /products/SKU-003` then shows 98. Cancel the order and it shows 100 again.
7. **CORS**:
   - `curl -si -H "Origin: http://localhost:5173" http://localhost:8081/products` includes
     `Access-Control-Allow-Origin: http://localhost:5173`.
   - With `-H "Origin: http://evil.example"`, the answer is `403`.
   - The preflight
     `curl -si -X OPTIONS -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET" http://localhost:8081/products`
     returns `200` with the allow headers.
   - Restart without `CORS_ALLOWED_ORIGINS`. No `Access-Control-*` header is returned for any
     origin.
8. **Kafka down**: `docker compose stop kafka` in ecommerce-infra. Scenarios 1–4 still work, and
   `/actuator/health` is `UP`. Start Kafka again afterwards.
9. **Docs**: open http://localhost:8081/swagger-ui.html. It shows both endpoints, every parameter,
   every response field and the 200/400/404/405/500 responses. `curl -s
   http://localhost:8081/v3/api-docs` returns the OpenAPI JSON, which contains only `/products` paths.
10. **Clean up the shell**: `Remove-Item Env:CORS_ALLOWED_ORIGINS -ErrorAction SilentlyContinue`
    before running the quality gate again. The tests set the property explicitly, but a leftover
    variable must never be what makes them pass.
11. **Existing local database**: on a database seeded before this feature, the restart applies V5
    and re-runs the seed. The names appear and the quantities changed by earlier orders stay as they
    were.

## Follow-ups outside this repository (ecommerce-infra compose)

The ecommerce-infra compose already runs Inventory (`inventory` service, profile `apps`) with host
port `8081` mapped to container `8080`, so the port needs no change. Add
`CORS_ALLOWED_ORIGINS: <frontend origin>` (for example `http://localhost:5173`) to its `environment`
once the frontend exists. `docs/events.md` is unchanged: no event contract changes.

## Validation 2026-11-17

Run against ecommerce-infra with the compose `inventory` service rebuilt from
`feature/US-04-products-api` (`docker compose --profile apps up -d --build inventory`, with
`CORS_ALLOWED_ORIGINS=http://localhost:5173` supplied through a temporary override file; the compose
file itself was not changed), on host port 8081.

| Scenario | Result |
|---|---|
| 1 List | Pass: five named sample products in id order, `total` 5, `limit` 50, `offset` 0; `SKU-005` listed with 0 |
| 2 Paging | Pass: `limit=2&offset=3` → `SKU-004`, `SKU-005`; `offset=10` → empty `items`, `total` 5 |
| 3 One product | Pass: `SKU-003` 200; `SKU-999` 404 `application/problem+json` with `productId`; `sku-003` 404 |
| 4 Bad input | Pass: `limit=0`, `201`, `offset=-1`, `abc`, `limit=`, `limit=1&limit=2` → 400 with one `errors` entry each |
| 5 Read-only | Pass: POST/PUT/PATCH/DELETE → 405, `Allow: GET` |
| 6 Stock follows reservations | Pass: `SKU-003` 98 → 96 after an `orders.order-placed` for 2 → 98 after the cancellation |
| 7 CORS | Pass: allowed origin gets `Access-Control-Allow-Origin`; `http://evil.example` → 403; preflight 200 with `GET,HEAD`, max age 1800 |
| 8 Kafka down | Pass: list, one, 404 and 400 answer normally with Kafka stopped; health `UP`; after restarting Kafka a reserve/cancel on `SKU-001` (10 → 9 → 10) proves the consumers reconnected |
| 9 Docs | Pass: `/swagger-ui.html` → 302 → `/swagger-ui/index.html` 200; `/v3/api-docs` lists only `/products` and `/products/{id}` |
| 10 Clean shell | Pass: the variable was never set in the shell (the gate script also removes it) |
| 11 Existing local database | Pass: V5 and the re-run seed applied on start; names set, earlier quantity changes (`SKU-003` 98) kept |

Consequence for ecommerce-infra: the running `inventory` container now runs the US-04 code and the
local `inventory` database is at V5. An image built from `main` before US-04 can no longer start on
that database (its local seed inserts rows without a name); rebuild from `main` after the merge.
