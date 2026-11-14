# Architecture Decision Records

Architectural decisions for `ecommerce-inventory` are recorded here, as the project constitution
requires (Documentation Standards).

## Conventions

- One decision per file, named `NNNN-short-title.md` with a four-digit sequential number
  (`0001-...`, `0002-...`).
- Numbers are never reused or renumbered. To reverse a decision, write a new ADR and set the old
  one's status to `Superseded by NNNN`. The superseded ADR is kept.
- Start from [`template.md`](template.md) (Context / Decision / Consequences).
- Add every new ADR to the index below in the same PR.
- Adding any HTTP API (constitution Principle II) or a runtime dependency that duplicates an
  existing capability requires an ADR before the PR can be merged.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-postgresql-for-inventory.md) | PostgreSQL for Inventory instead of a NoSQL store | Accepted |
| [0002](0002-atomic-stock-reservation-strategy.md) | Atomic stock reservation: pessimistic row locks ordered by productId | Accepted |
| [0003](0003-inventory-in-order-saga.md) | Inventory takes part in the order saga in parallel with Payments | Accepted |
| [0004](0004-outcome-stored-with-reservation.md) | Outcome message stored with the reservation, published after commit | Accepted |
| [0005](0005-per-order-serialisation.md) | Per-order serialisation of placement and cancellation | Accepted |
