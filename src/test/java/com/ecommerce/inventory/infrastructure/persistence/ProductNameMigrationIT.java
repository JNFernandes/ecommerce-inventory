package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ecommerce.inventory.support.PostgresTestContainer;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V5 names every product that existed before it after its product id and leaves no default behind
 * (US-04 FR-017, FR-018). Runs Flyway directly on its own database: first up to V4, then V5.
 */
class ProductNameMigrationIT {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateWithExistingProducts() {
        PostgreSQLContainer<?> postgres = PostgresTestContainer.instance();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PostgresTestContainer.isolatedDatabase("product_name_migration"),
                postgres.getUsername(),
                postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade; create schema public;");

        flyway(dataSource, "4").migrate();
        jdbc.update("insert into stock_items (product_id, available_quantity) values ('SKU-A', 3), ('SKU-B', 0)");
        flyway(dataSource, "latest").migrate();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    @DisplayName("should name every existing product after its product id and keep its stock")
    void shouldBackfillNamesFromProductIds() {
        List<Map<String, Object>> rows =
                jdbc.queryForList("select product_id, name, available_quantity from stock_items order by product_id");

        assertThat(rows)
                .extracting(row -> row.get("product_id"), row -> row.get("name"), row -> row.get("available_quantity"))
                .containsExactly(tuple("SKU-A", "SKU-A", 3), tuple("SKU-B", "SKU-B", 0));
    }

    @Test
    @DisplayName("should refuse a product without a name")
    void shouldRefuseMissingName() {
        assertThatThrownBy(() ->
                        jdbc.update("insert into stock_items (product_id, available_quantity) values ('SKU-C', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"name\"");
    }

    @Test
    @DisplayName("should refuse a blank name")
    void shouldRefuseBlankName() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into stock_items (product_id, name, available_quantity) values ('SKU-D', '   ', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_stock_items_name_not_blank");
    }
}
