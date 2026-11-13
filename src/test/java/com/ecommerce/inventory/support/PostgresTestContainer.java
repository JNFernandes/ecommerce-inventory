package com.ecommerce.inventory.support;

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

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        return container;
    }
}
