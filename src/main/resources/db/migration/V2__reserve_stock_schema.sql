-- V2: schema for US-01 (reserve stock when an order is placed).
-- See specs/001-reserve-stock/data-model.md section 3. Invariants are enforced here as well as in
-- the domain (constitution Principle IV).

-- Products Inventory can supply. Stock never goes negative (FR-009).
create table stock_items (
    product_id         varchar(64) primary key,
    available_quantity integer     not null,
    updated_at         timestamptz not null default now(),
    constraint ck_stock_items_available_not_negative check (available_quantity >= 0)
);

-- One reservation per order (FR-013), Reserved or Rejected. A reason exists only when Rejected.
create table reservations (
    id               uuid        primary key,
    order_id         uuid        not null,
    status           varchar(16) not null,
    rejection_reason varchar(64),
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    constraint uq_reservations_order_id unique (order_id),
    constraint ck_reservations_status check (status in ('RESERVED', 'REJECTED')),
    constraint ck_reservations_reason_iff_rejected
        check ((status = 'REJECTED') = (rejection_reason is not null))
);

-- Consolidated items of a reservation. Unknown products are recorded too, so there is no foreign key
-- to stock_items. Failing items carry the failure kind and the stock seen at decision time.
create table reservation_items (
    reservation_id        uuid        not null references reservations (id) on delete cascade,
    product_id            varchar(64) not null,
    requested_quantity    bigint      not null,
    failure_kind          varchar(32),
    available_at_decision integer,
    primary key (reservation_id, product_id),
    constraint ck_reservation_items_requested_positive check (requested_quantity > 0),
    constraint ck_reservation_items_failure_kind
        check (failure_kind in ('UNKNOWN_PRODUCT', 'INSUFFICIENT_STOCK')),
    constraint ck_reservation_items_available_not_negative check (available_at_decision >= 0)
);

-- The exact outcome message of each reservation, saved in the reservation transaction and
-- republished verbatim when the same inbound message is redelivered (FR-016, FR-017).
create table outcome_messages (
    event_id       uuid         primary key,
    reservation_id uuid         not null references reservations (id),
    topic          varchar(255) not null,
    message_key    varchar(255) not null,
    payload        jsonb        not null,
    created_at     timestamptz  not null,
    constraint uq_outcome_messages_reservation unique (reservation_id)
);

-- Inbound events already handled, for consumer idempotency (FR-015).
create table processed_events (
    event_id       uuid         primary key,
    topic          varchar(255) not null,
    outcome        varchar(32)  not null,
    reservation_id uuid         not null references reservations (id),
    processed_at   timestamptz  not null,
    constraint ck_processed_events_outcome
        check (outcome in ('RESERVATION_DECIDED', 'DUPLICATE_ORDER_IGNORED'))
);

-- Outcome messages that could not be published after the commit (FR-023). Redelivered by US-03.
create table outbound_dead_letters (
    id            uuid         primary key,
    event_id      uuid         not null,
    topic         varchar(255) not null,
    message_key   varchar(255) not null,
    payload       jsonb        not null,
    status        varchar(16)  not null,
    retry_count   integer      not null default 0,
    next_retry_at timestamptz  not null,
    last_error    text         not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint uq_outbound_dead_letters_event_id unique (event_id),
    constraint ck_outbound_dead_letters_status check (status in ('PENDING', 'RESOLVED', 'EXHAUSTED')),
    constraint ck_outbound_dead_letters_retry_count check (retry_count >= 0)
);

create index ix_outbound_dead_letters_due on outbound_dead_letters (status, next_retry_at);

-- Consumed messages that could not be processed (FR-003, FR-024). Raw value kept as received.
create table consumed_dead_letters (
    id            uuid         primary key,
    topic         varchar(255) not null,
    partition     integer      not null,
    record_offset bigint       not null,
    message_key   text,
    payload       text,
    error         text         not null,
    attempts      integer      not null,
    failed_at     timestamptz  not null,
    constraint uq_consumed_dead_letters_record unique (topic, partition, record_offset),
    constraint ck_consumed_dead_letters_attempts check (attempts >= 1)
);
