-- V4: outbound dead-letter retry worker (US-03). See specs/003-outbound-retry/data-model.md.
-- The only missing column: when a row was delivered by the worker. Set exactly when RESOLVED.
alter table outbound_dead_letters add column resolved_at timestamptz;

update outbound_dead_letters set resolved_at = updated_at where status = 'RESOLVED';

alter table outbound_dead_letters
    add constraint ck_outbound_dead_letters_resolved_at_iff_resolved
        check ((status = 'RESOLVED') = (resolved_at is not null));
