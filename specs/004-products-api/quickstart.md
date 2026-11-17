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

When Inventory is added to the shared compose: publish container port `8080` as host `8081` (kafka-ui
keeps 8080), and set `CORS_ALLOWED_ORIGINS` to the frontend origin. `docs/events.md` is unchanged:
no event contract changes.
