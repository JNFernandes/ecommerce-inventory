# Kafka Contracts: US-02 Release Stock

Source of truth: `ecommerce-infra/docs/events.md`. Envelope rules are the same as US-01
([001 contracts](../../001-reserve-stock/contracts/kafka-events.md)).

## Consumed: `orders.order-cancelled`

- **Key**: order id. **Group**: `inventory-service` (same group as `orders.order-placed`).
- `aggregateId` is the **order id**.

```json
{
  "eventId": "2c9d8e7f-6a5b-4c3d-9e8f-7a6b5c4d3e2f",
  "occurredAt": "2026-11-13T10:20:00.000Z",
  "aggregateId": "3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "version": 1,
  "customerId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "reason": "payment failed"
}
```

Validation (FR-002). Any failure → inbound dead-letter on the first attempt, offset committed:

| Field | Rule |
|---|---|
| body | valid JSON object |
| `eventId`, `aggregateId` | required, UUID |
| `occurredAt` | required, ISO 8601 timestamp |
| `version` | required, integer, must be `1` |
| `customerId`, `reason`, unknown fields | optional / ignored |

## Produced: `inventory.stock-released`

- **Key** = `aggregateId` = **reservation id**. The order is in `orderId`.
- `items` are the consolidated reserved quantities, one per product, sorted by `productId`.
- `occurredAt` equals `releasedAt`.

```json
{
  "eventId": "5b4a3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d",
  "occurredAt": "2026-11-13T10:20:00.512Z",
  "aggregateId": "0a1b2c3d-4e5f-4061-8728-394a5b6c7d8e",
  "version": 1,
  "orderId": "3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "items": [
    { "productId": "SKU-001", "quantity": 3 },
    { "productId": "SKU-002", "quantity": 5 }
  ],
  "releasedAt": "2026-11-13T10:20:00.512Z"
}
```

Nothing is published when a cancellation is ignored or recorded as arriving first, nor for a placement
ignored because of a cancellation marker.

## Changes required in `ecommerce-infra/docs/events.md`

On a branch created from `main` after the US-01 contract PR is merged:

1. Topics table: `orders.order-cancelled` consumer status for Inventory → implemented;
   `inventory.stock-released` → implemented (published).
2. `inventory.stock-released`: drop "(planned)"; state that `items` are the consolidated reserved
   quantities, sorted by `productId`, 64-bit integers.
3. Order saga section: note that a cancellation arriving before the placement prevents the
   reservation (Inventory records it and ignores the later placement).
