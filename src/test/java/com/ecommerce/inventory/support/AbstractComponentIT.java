package com.ecommerce.inventory.support;

import com.ecommerce.inventory.application.ReserveStockService;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Base class for component tests: the whole service with a real PostgreSQL and a real Kafka. Other
 * services are simulated by producing their events with {@link TestKafkaClient}.
 *
 * <p>Every spy and property override lives here so all component tests share one Spring context. A
 * second context would start a second listener in the same consumer group and steal messages.
 * Inbound retries are shortened (2 retries, 50 ms) to keep failure tests fast.
 */
@SpringBootTest(
        properties = {
            "inventory.kafka.consumer-retry.max-retries=2",
            "inventory.kafka.consumer-retry.initial-interval=50ms",
            "inventory.kafka.consumer-retry.max-interval=200ms"
        })
public abstract class AbstractComponentIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainer.instance();

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = KafkaTestContainer.instance();

    private final TestKafkaClient kafka = new TestKafkaClient(KAFKA.getBootstrapServers());

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ReserveStockService reserveStockService;

    @MockitoSpyBean
    private OutboundMessagePublisher publisher;

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

    /**
     * Spy on the reserve-stock use case, reset after each test.
     *
     * @return the spy
     */
    protected ReserveStockService reserveStockService() {
        return reserveStockService;
    }

    /**
     * Spy on the outbound publisher, reset after each test.
     *
     * @return the spy
     */
    protected OutboundMessagePublisher publisher() {
        return publisher;
    }
}
