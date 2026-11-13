package com.ecommerce.inventory.support;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Arranges and reads {@code stock_items} rows directly with SQL. */
public final class StockFixtures {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the fixtures.
     *
     * @param jdbcTemplate JDBC access to the test database
     */
    public StockFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts or replaces a stock row.
     *
     * @param productId product id
     * @param availableQuantity available quantity
     */
    public void given(String productId, int availableQuantity) {
        jdbcTemplate.update("""
                insert into stock_items (product_id, available_quantity, updated_at) values (?, ?, now())
                on conflict (product_id) do update set available_quantity = excluded.available_quantity
                """, productId, availableQuantity);
    }

    /**
     * Reads the available quantity of a product.
     *
     * @param productId product id
     * @return the available quantity, or empty for an unknown product
     */
    public Optional<Integer> available(String productId) {
        return jdbcTemplate
                .queryForList(
                        "select available_quantity from stock_items where product_id = ?", Integer.class, productId)
                .stream()
                .findFirst();
    }
}
