package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.StockFixtures;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

/** The local profile seeds the sample products once and never resets them (FR-026, spec Story 6). */
@ActiveProfiles("local")
class LocalSeedIT extends AbstractPostgresIT {

    private static final Map<String, Integer> SAMPLE =
            Map.of("SKU-001", 10, "SKU-002", 25, "SKU-003", 100, "SKU-004", 5, "SKU-005", 0);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("should apply the local seed through Flyway when the local profile is active")
    void shouldApplySeedWithLocalProfile() {
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .contains("classpath:db/local");
        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history"
                                + " where script = 'R__seed_local_stock.sql' and success",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("should create the five documented sample products and never reset or duplicate them")
    void shouldSeedWithoutResetting() {
        StockFixtures stock = new StockFixtures(jdbc);
        jdbc.execute("truncate table stock_items cascade");

        runSeed();
        SAMPLE.forEach(
                (productId, quantity) -> assertThat(stock.available(productId)).contains(quantity));

        stock.given("SKU-003", 42);
        runSeed();
        assertThat(stock.available("SKU-003")).contains(42);
        assertThat(jdbc.queryForObject("select count(*) from stock_items", Integer.class))
                .isEqualTo(5);
    }

    private void runSeed() {
        new ResourceDatabasePopulator(new ClassPathResource("db/local/R__seed_local_stock.sql")).execute(dataSource);
    }
}
