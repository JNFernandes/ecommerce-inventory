# 0001. PostgreSQL for Inventory instead of a NoSQL store

- **Status**: Accepted
- **Date**: 2026-11-13

## Context

Inventory owns product stock and stock reservations. Its core invariants are strict:

- available stock is never negative;
- a reservation is all-or-nothing across every item of an order;
- concurrent orders for the same product must not oversell (no lost updates);
- at most one reservation exists per order, and it is released at most once.

On top of that, the constitution (Principles III and VIII) requires that each state change, the
processed-event record used for consumer idempotency and any outbound dead-letter record are
written in **one transaction**, and that a retry worker can claim dead-letter rows concurrently
(`FOR UPDATE SKIP LOCKED`).

The system follows database-per-service: each service owns its store and no other service reads
it. The rest of the system already uses PostgreSQL for write-side invariants (Orders, Payments) and
MongoDB only for a read model in Orders. Inventory has no read-model or flexible-schema needs: its
data is small, relational (stock items, reservations, reservation lines) and accessed by key.

## Decision

We will store all Inventory state in its own **PostgreSQL** database (`inventory`, provided locally
by ecommerce-infra on `localhost:5433`), accessed through Spring Data JPA, with the schema managed
exclusively by Flyway. No other service connects to this database.

Invariants are enforced twice: in the domain model, and by the database
(`CHECK (available_quantity >= 0)`, a unique constraint on the reservation's `order_id`, foreign
keys).

We will not use a NoSQL store for Inventory.

## Consequences

- Multi-row, multi-table ACID transactions are available, so all-or-nothing reservation,
  idempotency records and the outbound dead-letter record commit or roll back together.
- Row-level locks (`SELECT ... FOR UPDATE`, `SKIP LOCKED`) give a precise, well-understood
  concurrency model for stock (see ADR 0002) and for the retry worker.
- The `CHECK` and unique constraints are a safety net: a domain bug cannot oversell or create a
  second reservation for an order.
- The team reuses the same database technology, tooling and Testcontainers setup as Orders and
  Payments.
- Write scaling is limited to one primary. This is acceptable for the expected load; stock for a
  very hot product serialises on its row, which is the correct behaviour for an oversell-free
  system.
- A JPA persistence model must be mapped explicitly to and from the domain model (Principle I),
  which costs some boilerplate.
