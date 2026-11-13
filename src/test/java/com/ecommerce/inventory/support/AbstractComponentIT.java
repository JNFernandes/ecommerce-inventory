package com.ecommerce.inventory.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Base class for component tests: the whole service with a real PostgreSQL and a real Kafka. Other
 * services are simulated by producing their events with {@link TestKafkaClient}.
 */
@SpringBootTest
public abstract class AbstractComponentIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainer.instance();

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = KafkaTestContainer.instance();

    private final TestKafkaClient kafka = new TestKafkaClient(KAFKA.getBootstrapServers());

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private StockFixtures stock;

    @BeforeEach
    void cleanDatabase() {
        DatabaseCleaner.truncateAll(jdbcTemplate);
        stock = new StockFixtures(jdbcTemplate);
    }

    /**
     * JDBC access for arranging and asserting database state.
     *
     * @return the JDBC template of the test context
     */
    protected JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    /**
     * Client that plays the part of the other services on Kafka.
     *
     * @return the Kafka test client
     */
    protected TestKafkaClient kafka() {
        return kafka;
    }

    /**
     * Fixtures for stock rows, recreated before each test.
     *
     * @return the stock fixtures
     */
    protected StockFixtures stock() {
        return stock;
    }
}
