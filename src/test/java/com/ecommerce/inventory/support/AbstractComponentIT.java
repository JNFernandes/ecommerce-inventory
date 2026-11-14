package com.ecommerce.inventory.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.inventory.application.ReleaseStockService;
import com.ecommerce.inventory.application.ReserveStockService;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
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
    private ReleaseStockService releaseStockService;

    @MockitoSpyBean
    private OutboundMessagePublisher publisher;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    private StockFixtures stock;

    @BeforeEach
    void cleanDatabase() {
        DatabaseCleaner.truncateAll(jdbcTemplate);
        stock = new StockFixtures(jdbcTemplate);
    }

    @BeforeEach
    void waitForListenerAssignment() {
        assertThat(listeners.getListenerContainers()).isNotEmpty();
        for (MessageListenerContainer container : listeners.getListenerContainers()) {
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> container.getAssignedPartitions() != null
                            && !container.getAssignedPartitions().isEmpty());
        }
    }

    /**
     * Counts rows, for example {@code count("reservations")} or
     * {@code count("processed_events where outcome = 'STOCK_RELEASED'")}.
     *
     * @param tableAndCondition table name, optionally followed by a {@code where} clause
     * @return the number of rows
     */
    protected int count(String tableAndCondition) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from " + tableAndCondition, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * One order line of an {@code orders.order-placed} message.
     *
     * @param productId product id
     * @param quantity quantity
     * @return the JSON object text
     */
    protected static String line(String productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + ",\"unitPrice\":1.5}";
    }

    /**
     * A valid {@code orders.order-placed} message.
     *
     * @param eventId event id
     * @param orderId order id ({@code aggregateId})
     * @param lines order lines built with {@link #line}
     * @return the JSON text
     */
    protected static String orderPlaced(UUID eventId, UUID orderId, String... lines) {
        return "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-11-13T10:15:30.123Z\","
                + "\"aggregateId\":\"" + orderId + "\",\"version\":1,"
                + "\"customerId\":\"" + UUID.randomUUID() + "\","
                + "\"items\":[" + String.join(",", lines) + "],\"totalAmount\":10}";
    }

    /**
     * A valid {@code orders.order-cancelled} message.
     *
     * @param eventId event id
     * @param orderId order id ({@code aggregateId})
     * @return the JSON text
     */
    protected static String orderCancelled(UUID eventId, UUID orderId) {
        return "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-11-13T10:20:00.000Z\","
                + "\"aggregateId\":\"" + orderId + "\",\"version\":1,"
                + "\"customerId\":\"" + UUID.randomUUID() + "\",\"reason\":\"payment failed\"}";
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
     * Spy on the release-stock use case, reset after each test.
     *
     * @return the spy
     */
    protected ReleaseStockService releaseStockService() {
        return releaseStockService;
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
