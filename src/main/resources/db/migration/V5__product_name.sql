-- V5: product names for the read-only catalog (US-04). See specs/004-products-api/data-model.md §1.
-- Every product has a non-blank name. Existing rows are named after their product id; the default
-- only serves the backfill and is dropped, so every later insert must state a name.
alter table stock_items add column name varchar(200) not null default '';

update stock_items set name = product_id;

alter table stock_items alter column name drop default;

alter table stock_items
    add constraint ck_stock_items_name_not_blank check (btrim(name) <> '');
