# Contract: Products API (US-04)

Read-only, public, unauthenticated. Base URL locally: `http://localhost:8081`. All bodies are JSON
(`application/json`, errors `application/problem+json`). The generated OpenAPI document
(`/v3/api-docs`, viewer `/swagger-ui.html`) must match this file.

## GET /products

List products in catalog order (product id ascending, byte order).

| Query parameter | Type | Default | Allowed |
|---|---|---|---|
| `limit` | integer | 50 | 1..200 |
| `offset` | integer | 0 | ≥ 0 |

A parameter that is empty, repeated, not a whole number, or out of range is a `400`. Unknown
parameters are ignored.

**200**

```json
{
  "items": [
    { "id": "SKU-001", "name": "Wireless Mouse", "availableQuantity": 10 },
    { "id": "SKU-002", "name": "Mechanical Keyboard", "availableQuantity": 25 }
  ],
  "total": 5,
  "limit": 2,
  "offset": 0
}
```

- `items`: at most `limit` products, after skipping `offset` products. Empty when `offset ≥ total`.
- `total`: number of products in the catalog, from the same snapshot as `items`.
- `limit`, `offset`: the values applied, defaults included.
- Products with `availableQuantity` 0 are included.

**400** (example: `GET /products?limit=201&offset=-1`)

```json
{
  "type": "about:blank",
  "title": "Invalid request parameters",
  "status": 400,
  "detail": "One or more request parameters are invalid.",
  "instance": "/products",
  "errors": [
    { "parameter": "limit", "rejectedValue": "201", "message": "must be less than or equal to 200" },
    { "parameter": "offset", "rejectedValue": "-1", "message": "must be greater than or equal to 0" }
  ]
}
```

Other `errors[].message` values: `must be greater than or equal to 1` (limit 0), `must be a whole
number` (`abc`, `1.5`), `must not be empty` (`limit=`), `must be given at most once` (repeated).

## GET /products/{id}

`{id}` is matched exactly (case-sensitive), after URL decoding (`SKU%20X` looks up `SKU X`). An
encoded slash (`%2F`) is refused by the HTTP server with `400` before reaching the application; that
body is the server's default error body, not a problem detail.

**200**

```json
{ "id": "SKU-003", "name": "USB-C Hub", "availableQuantity": 100 }
```

**404**

```json
{
  "type": "about:blank",
  "title": "Product not found",
  "status": 404,
  "detail": "Product SKU-999 not found.",
  "instance": "/products/SKU-999",
  "productId": "SKU-999"
}
```

## Common responses

| Status | When | Body |
|---|---|---|
| 404 | Any path outside the documented ones | problem detail, `title` `Not Found` |
| 405 | `POST`, `PUT`, `PATCH`, `DELETE` on `/products` or `/products/{id}` | problem detail; `Allow: GET` (HEAD and OPTIONS are still answered) |
| 500 | Unexpected failure (for example, the database is unreachable) | problem detail, `title` `Internal error`, fixed generic `detail`; never a stack trace, SQL or class name |

Invalid client input never produces a `5xx`.

## CORS

- Origins come only from `CORS_ALLOWED_ORIGINS` (comma separated). Empty by default: no CORS headers,
  so browsers block cross-origin calls.
- Allowed origin: `Access-Control-Allow-Origin: <origin>`; methods `GET`, `HEAD`; preflight
  `OPTIONS` answered `200`; preflight cached for 1800 s; no credentials.
- Origin not in the list (while the list is not empty): `403`.

## Semantics

- `availableQuantity` is a **snapshot** of the stock saved by the reservation and release flows. It
  can change right after it is read. Reserving the order is the authoritative check: an order for a
  product shown as available can still be rejected.
- Reads take no locks and never delay reservations.
- The API works while Kafka is unreachable; it needs only PostgreSQL.
- No authentication, no caching headers beyond defaults.

## Other HTTP endpoints (constitution 1.1.2)

| Path | Purpose |
|---|---|
| `GET /actuator/health` (+ `/liveness`, `/readiness`) | health |
| `GET /v3/api-docs` (+ `/v3/api-docs.yaml`, `/v3/api-docs/swagger-config`) | OpenAPI 3 document of the two endpoints above |
| `GET /swagger-ui.html` | Swagger UI (redirects to `/swagger-ui/index.html`; static resources under `/swagger-ui/`) |

Spring Boot's internal `/error` path renders errors for requests the server rejects before a
controller; it is not an API. No other path is mapped (proven by a test).
