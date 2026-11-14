package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.inventory.InventoryApplication;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Graceful shutdown of the retry worker in a real application context (US-03 FR-015, SC-006): the row
 * being published is finished, rows not yet claimed stay as they were, and closing is bounded. The test
 * owns the context so it can close it while a publish is in progress.
 */
class OutboundRetryShutdownIT {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainer.instance();
    private static final String DATABASE_URL = PostgresTestContainer.isolatedDatabase("shutdown_it");
    private static final Duration PUBLISH_BLOCK = Duration.ofSeconds(2);
    private static final CountDownLatch PUBLISHING = new CountDownLatch(1);

    @Test
    @DisplayName("should finish the row in progress, leave the others PENDING and close within the bound")
    void shouldStopGracefully() throws Exception {
        try (Connection connection = connect()) {
            truncate(connection);
            ConfigurableApplicationContext context = new SpringApplicationBuilder(
                            InventoryApplication.class, BlockingPublisherConfiguration.class)
                    .run(arguments());
            for (int i = 0; i < 5; i++) {
                insertDue(connection, i);
            }
            assertThat(PUBLISHING.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            context.close();
            Duration closing = Duration.ofNanos(System.nanoTime() - start);

            assertThat(closing).isLessThan(Duration.ofSeconds(15 + 5));
            Map<String, Integer> statuses = statuses(connection);
            assertThat(statuses).containsEntry("RESOLVED", 1).containsEntry("PENDING", 4);
            assertThat(count(connection, "status = 'PENDING' and retry_count = 0 and resolved_at is null"))
                    .isEqualTo(4);
            assertThat(count(connection, "status = 'RESOLVED' and resolved_at is not null"))
                    .isEqualTo(1);
            await().atMost(Duration.ofSeconds(1)).until(() -> !context.isActive());
        }
    }

    /**
     * Command-line arguments, because they override {@code application.yml}; default properties would
     * not, and the context would connect to the local development database.
     */
    private static String[] arguments() {
        return new String[] {
            "--spring.datasource.url=" + DATABASE_URL,
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.kafka.bootstrap-servers=localhost:1",
            "--server.port=0",
            "--inventory.outbound-retry.enabled=true",
            "--inventory.outbound-retry.interval=100ms"
        };
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void truncate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("do $$ begin if to_regclass('outbound_dead_letters') is not null then"
                    + " truncate outbound_dead_letters; end if; end $$");
        }
    }

    private static void insertDue(Connection connection, int index) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("insert into outbound_dead_letters (id, event_id, topic, message_key, payload, status,"
                    + " retry_count, next_retry_at, last_error, created_at, updated_at) values ('"
                    + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 'inventory.stock-reserved', 'k" + index
                    + "', '{}'::jsonb, 'PENDING', 0, now() - interval '" + (10 - index) + " seconds',"
                    + " 'broker down', now(), now())");
        }
    }

    private static Map<String, Integer> statuses(Connection connection) throws SQLException {
        Map<String, Integer> statuses = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("select status, count(*) from outbound_dead_letters group by status")) {
            while (rows.next()) {
                statuses.put(rows.getString(1), rows.getInt(2));
            }
        }
        return statuses;
    }

    private static int count(Connection connection, String condition) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("select count(*) from outbound_dead_letters where " + condition)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Replaces the Kafka publisher with one that blocks, so the context closes mid-publish. A test
     * configuration, so component scanning in other test contexts ignores it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class BlockingPublisherConfiguration {

        @Bean
        @Primary
        OutboundMessagePublisher blockingPublisher() {
            return (OutboundMessage message) -> {
                PUBLISHING.countDown();
                try {
                    Thread.sleep(PUBLISH_BLOCK);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            };
        }
    }
}
