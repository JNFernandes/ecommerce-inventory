package com.ecommerce.inventory.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Empties every application table between tests, keeping the Flyway history. */
public final class DatabaseCleaner {

    private DatabaseCleaner() {}

    /**
     * Truncates every table in the {@code public} schema except {@code flyway_schema_history}.
     *
     * @param jdbcTemplate JDBC access to the test database
     */
    public static void truncateAll(JdbcTemplate jdbcTemplate) {
        List<String> tables = jdbcTemplate.queryForList(
                "select tablename from pg_tables where schemaname = 'public'"
                        + " and tablename <> 'flyway_schema_history'",
                String.class);
        if (!tables.isEmpty()) {
            jdbcTemplate.execute("truncate table " + String.join(", ", tables) + " cascade");
        }
    }
}
