-- Local development only (FR-026): sample products for trying the flow against ecommerce-infra.
-- Loaded only by the 'local' Spring profile (application-local.yml); never in tests or production.
-- Existing rows are left alone, so stock is never reset or duplicated when this runs again.
insert into stock_items (product_id, available_quantity, updated_at)
values ('SKU-001', 10, now()),
       ('SKU-002', 25, now()),
       ('SKU-003', 100, now()),
       ('SKU-004', 5, now()),
       ('SKU-005', 0, now())
on conflict (product_id) do nothing;
