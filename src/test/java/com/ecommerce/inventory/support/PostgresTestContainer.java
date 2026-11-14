package com.ecommerce.inventory.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;

/** One PostgreSQL container per JVM, shared by every test class (singleton container pattern). */
public final class PostgresTestContainer {

    private static final PostgreSQLContainer<?> INSTANCE = start();

    private PostgresTestContainer() {}

    /**
     * Returns the started container.
     *
     * @return the shared PostgreSQL container
     */
    public static PostgreSQLContainer<?> instance() {
        return INSTANCE;
    }

    /**
     * Creates, if needed, a separate database in the shared container and returns its JDBC URL. Contexts
     * that run the outbound retry worker use one each: Spring caches contexts, so a worker in a cached
     * context would otherwise process rows that other test classes insert.
     *
     * @param name database name (lower case letters and underscores)
     * @return the JDBC URL of the database
     */
    public static synchronized String isolatedDatabase(String name) {
        if (!name.matches("[a-z_]+")) {
            throw new IllegalArgumentException("invalid database name " + name);
        }
        try (Connection connection = DriverManager.getConnection(
                        INSTANCE.getJdbcUrl(), INSTANCE.getUsername(), INSTANCE.getPassword());
                Statement statement = connection.createStatement()) {
            boolean exists;
            try (ResultSet rows = statement.executeQuery("select 1 from pg_database where datname = '" + name + "'")) {
                exists = rows.next();
            }
            if (!exists) {
                statement.execute("create database " + name);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test database " + name, e);
        }
        return INSTANCE.getJdbcUrl().replace("/" + INSTANCE.getDatabaseName(), "/" + name);
    }

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        return container;
    }
}
