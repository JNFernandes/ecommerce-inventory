-- V3: schema for US-02 (release reserved stock when an order is cancelled).
-- See specs/002-release-stock/data-model.md section 3.

-- Reservations can now be released (Reserved -> Released only), with the time of the release.
alter table reservations drop constraint ck_reservations_status;
alter table reservations
    add constraint ck_reservations_status check (status in ('RESERVED', 'REJECTED', 'RELEASED'));
alter table reservations add column released_at timestamptz;
alter table reservations
    add constraint ck_reservations_released_at_iff_released
        check ((status = 'RELEASED') = (released_at is not null));

-- A reservation can now have two outcome messages over its life (reserve and release).
alter table outcome_messages drop constraint uq_outcome_messages_reservation;
create index ix_outcome_messages_reservation on outcome_messages (reservation_id);

-- Processed events record the exact message they produced (republished on redelivery) and can exist
-- without a reservation (early cancellations and placements of already cancelled orders).
alter table processed_events alter column reservation_id drop not null;
alter table processed_events add column outcome_event_id uuid references outcome_messages (event_id);
update processed_events p
set outcome_event_id = o.event_id
from outcome_messages o
where o.reservation_id = p.reservation_id
  and p.outcome = 'RESERVATION_DECIDED';
alter table processed_events drop constraint ck_processed_events_outcome;
alter table processed_events
    add constraint ck_processed_events_outcome
        check (outcome in ('RESERVATION_DECIDED', 'DUPLICATE_ORDER_IGNORED', 'STOCK_RELEASED',
                           'CANCELLATION_IGNORED', 'CANCELLATION_RECORDED', 'PLACEMENT_CANCELLED'));
alter table processed_events
    add constraint ck_processed_events_outcome_message
        check ((outcome_event_id is not null) = (outcome in ('RESERVATION_DECIDED', 'STOCK_RELEASED')));

-- An order cancelled before Inventory handled its placement (race rule, FR-010 to FR-013). At most
-- one per order; a later placement of that order reserves nothing.
create table cancellation_markers (
    order_id   uuid        primary key,
    event_id   uuid        not null,
    created_at timestamptz not null
);
