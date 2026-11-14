# Kafka Contracts: US-03 Outbound Retry

**No contract changes.** The worker republishes messages that were already produced by US-01 and
US-02, byte for byte as stored:

| Topic | Contract | Produced originally by |
|---|---|---|
| `inventory.stock-reserved` | [001 contracts](../../001-reserve-stock/contracts/kafka-events.md) | US-01 |
| `inventory.stock-rejected` | [001 contracts](../../001-reserve-stock/contracts/kafka-events.md) | US-01 |
| `inventory.stock-released` | [002 contracts](../../002-release-stock/contracts/kafka-events.md) | US-02 |

Guarantees relevant to consumers (already stated in `ecommerce-infra/docs/events.md`):

- The republished message has the **same `eventId`, key and payload** as the original attempt.
- Delivery is **at-least-once**: the same `eventId` can arrive more than once (a redelivery republish
  and a worker publish, or a crash after publishing). Consumers deduplicate by `eventId`.
- A message can arrive later than other messages produced after it (it waited in the dead-letter
  store), so consumers must not assume Inventory messages arrive in decision order across orders.

`ecommerce-infra/docs/events.md` already states at-least-once delivery and deduplication by `eventId`;
no edit is required for this story.
