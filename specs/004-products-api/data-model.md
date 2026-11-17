# Data Model: Read-Only Products API (US-04)

## 1. Storage: `stock_items` (changed by `V5__product_name.sql`)

| Column | Type | Rules | Change |
|---|---|---|---|
| `product_id` | `varchar(64)` | primary key; matched exactly (case-sensitive) | unchanged |
| `name` | `varchar(200)` | `NOT NULL`, no default, `ck_stock_items_name_not_blank`: `btrim(name) <> ''` | **new**; existing rows backfilled with `product_id` |
| `available_quantity` | `integer` | `NOT NULL`, `ck_stock_items_available_not_negative` (≥ 0) | unchanged |
| `updated_at` | `timestamptz` | `NOT NULL` | unchanged |

- Only the reservation and release flows change `available_quantity`, under `PESSIMISTIC_WRITE`
  locks (ADR 0002). The catalog only reads (R2).
- `name` is written only by migrations, the local seed or manual SQL. No application code writes it
  (FR-020).
- Catalog order: `product_id` in byte order (`collate "C"`). Examples: `SKU-001` < `SKU-002` <
  `SKU-010` < `sku-001`.

Local sample data (`db/local/R__seed_local_stock.sql`, `local` profile only):

| `product_id` | `name` | `available_quantity` (on first insert) |
|---|---|---|
| `SKU-001` | Wireless Mouse | 10 |
| `SKU-002` | Mechanical Keyboard | 25 |
| `SKU-003` | USB-C Hub | 100 |
| `SKU-004` | 27-inch Monitor | 5 |
| `SKU-005` | Webcam HD | 0 |

The upsert sets `name` on existing rows and never changes their quantity.

## 2. Persistence model

`StockItemEntity` (package-private, `infrastructure.persistence`) gains `name` (`@Column(name = "name",
nullable = false, length = 200)`) with a getter and no setter. `StockItemMapper.toDomain` is
unchanged: the domain `StockItem` has no name.

## 3. Application read model (`application`)

Immutable records. Neither domain nor JPA types appear in them.

| Type | Fields | Rules |
|---|---|---|
| `ProductView` | `String productId`, `String name`, `int availableQuantity` | non-null, non-blank id and name; quantity ≥ 0 (checked in the compact constructor) |
| `ProductPage` | `List<ProductView> items`, `long total`, `int limit`, `int offset` | `items` copied with `List.copyOf`; `items.size() ≤ limit`; `total ≥ 0`; `limit` 1..200; `offset` ≥ 0 |
| `ProductNotFoundException` | `String productId` | unchecked; message `Product <id> not found` |

Port `application/port/ProductCatalogReader`:

| Method | Returns | Contract |
|---|---|---|
| `List<ProductView> findPage(int limit, int offset)` | at most `limit` products after skipping `offset`, in catalog order | no locks |
| `long count()` | number of products | no locks |
| `Optional<ProductView> findById(String productId)` | the product, exact id match | no locks; never `null` |

Service `application/ProductQueryService`:

| Method | Transaction | Behaviour |
|---|---|---|
| `ProductPage list(int limit, int offset)` | read-only, `REPEATABLE_READ` | checks arguments (`IllegalArgumentException`); `findPage` + `count` in one snapshot |
| `ProductView get(String productId)` | read-only | `findById` or `ProductNotFoundException` |

## 4. Web contract model (`web.dto`)

Separate records with `@Schema` documentation, mapped explicitly in the controller.

| Type | JSON fields |
|---|---|
| `ProductResponse` | `id` (string), `name` (string), `availableQuantity` (integer ≥ 0) |
| `ProductPageResponse` | `items` (array of `ProductResponse`), `total` (integer), `limit` (integer), `offset` (integer) |
| Problem detail (Spring `ProblemDetail`) | `type`, `title`, `status`, `detail`, `instance`; plus `productId` (404) or `errors[]` (400) |
| `errors[]` element (`web.dto.ParameterError`) | `parameter`, `rejectedValue`, `message` |

The full JSON shapes and examples are in [contracts/products-api.md](contracts/products-api.md).

## 5. Configuration (`InventoryProperties`)

| Property | Env variable | Default | Rule |
|---|---|---|---|
| `inventory.http.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | empty | comma separated; trimmed; blanks dropped; `*` or a wildcard fails startup |
| `server.port` | `SERVER_PORT` | `8081` (image: `8080`) | unchanged |

## 6. State transitions

None. The catalog is read-only. `available_quantity` transitions belong to US-01 (reserve) and US-02
(release).
