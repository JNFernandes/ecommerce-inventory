# Kafka Contracts: US-01 Reserve Stock

The service's only external interface is Kafka (constitution Principle II). The source of truth is
`ecommerce-infra/docs/events.md`; this file restates the parts US-01 depends on, with the exact
validation and JSON produced. Any difference MUST be resolved in `events.md` first (FR-022).

Common envelope on every message:

| Field | Type | Rule |
|---|---|---|
| `eventId` | UUID string | unique per event; a republish keeps it |
| `occurredAt` | ISO 8601 UTC timestamp | |
| `aggregateId` | UUID string | also the Kafka message key |
| `version` | integer | `1` |

## Consumed: `orders.order-placed`

- **Key**: order id. **Group**: `inventory-service` (`KAFKA_CONSUMER_GROUP_ID`).
- `aggregateId` is the **order id**.

```json
{
  "eventId": "8f0c6a1e-6a4b-4a43-9a1c-1f5f7f0a2b10",
  "occurredAt": "2026-11-13T10:15:30.123Z",
  "aggregateId": "3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "version": 1,
  "customerId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "items": [
    { "productId": "SKU-001", "quantity": 3, "unitPrice": 19.99 },
    { "productId": "SKU-002", "quantity": 5, "unitPrice": 4.5 }
  ],
  "totalAmount": 82.47
}
```

Validation (FR-002). Any failure → inbound dead-letter on the first attempt, offset committed:

| Field | Rule |
|---|---|
| body | valid JSON object |
| `eventId`, `aggregateId` | required, UUID |
| `occurredAt` | required, ISO 8601 timestamp |
| `version` | required, integer, must be `1` |
| `items` | required, at least one element |
| `items[].productId` | required, non-blank string, at most 64 characters |
| `items[].quantity` | required, JSON integer `> 0` (no strings, no decimals) |
| `customerId`, `unitPrice`, `totalAmount`, unknown fields | ignored |

## Produced: `inventory.stock-reserved`

- **Key** = `aggregateId` = **reservation id**. The order is in `orderId`.
- `items` are consolidated (one per product) and sorted by `productId`.
- `occurredAt` equals `reservedAt`.

```json
{
  "eventId": "c7d6e5f4-a3b2-4c1d-9e8f-7a6b5c4d3e2f",
  "occurredAt": "2026-11-13T10:15:30.456Z",
  "aggregateId": "0a1b2c3d-4e5f-4061-8728-394a5b6c7d8e",
  "version": 1,
  "orderId": "3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "items": [
    { "productId": "SKU-001", "quantity": 3 },
    { "productId": "SKU-002", "quantity": 5 }
  ],
  "reservedAt": "2026-11-13T10:15:30.456Z"
}
```

## Produced: `inventory.stock-rejected`

- **Key** = `aggregateId` = **reservation id**. The order is in `orderId`.
- `reason` is exactly one of: `insufficient stock`, `unknown product`,
  `unknown product and insufficient stock` (FR-008).
- `items` lists only the failing items, sorted by `productId`; `requested` is the consolidated
  quantity; `available` is the stock at decision time, `0` for an unknown product.
- `requested` (and `quantity` in `inventory.stock-reserved`) is a JSON integer that can exceed the
  32-bit range when duplicate lines are consolidated; consumers must read it as a 64-bit integer.
- `occurredAt` equals `rejectedAt`.

```json
{
  "eventId": "1e2d3c4b-5a69-4788-9766-554433221100",
  "occurredAt": "2026-11-13T10:16:02.001Z",
  "aggregateId": "9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a",
  "version": 1,
  "orderId": "7c6b5a49-3827-4165-9a8b-7c6d5e4f3a2b",
  "reason": "unknown product and insufficient stock",
  "items": [
    { "productId": "SKU-004", "requested": 6, "available": 5 },
    { "productId": "SKU-999", "requested": 1, "available": 0 }
  ],
  "rejectedAt": "2026-11-13T10:16:02.001Z"
}
```

## Changes required in `ecommerce-infra/docs/events.md`

Delivered as a linked PR in `ecommerce-infra` in the same change set:

1. Topics table: `orders.order-placed` consumer status for Inventory → implemented;
   `inventory.stock-reserved` and `inventory.stock-rejected` → implemented (published).
2. `inventory.stock-rejected`: list the three allowed `reason` values, state that only failing
   items are listed and that `available` is `0` for an unknown product.
3. Inventory events section heading: drop "(planned)" for the two implemented events.
4. `inventory.stock-reserved` `quantity` and `inventory.stock-rejected` `requested`: note that they
   are consolidated totals and may exceed the 32-bit integer range.
