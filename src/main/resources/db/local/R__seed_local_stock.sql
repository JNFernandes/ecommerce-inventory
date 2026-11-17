-- Local development only (FR-026): sample products for trying the flow against ecommerce-infra.
-- Loaded only by the 'local' Spring profile (application-local.yml); never in tests or production.
-- Names are upserted (US-04 FR-019); quantities of existing rows are never reset, and rows are never
-- duplicated when this runs again.
insert into stock_items (product_id, name, available_quantity, updated_at)
values ('SKU-001', 'Wireless Mouse', 10, now()),
       ('SKU-002', 'Mechanical Keyboard', 25, now()),
       ('SKU-003', 'USB-C Hub', 100, now()),
       ('SKU-004', '27-inch Monitor', 5, now()),
       ('SKU-005', 'Webcam HD', 0, now())
on conflict (product_id) do update set name = excluded.name;
