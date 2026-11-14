package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.inventory.support.DatabaseCleaner;
import com.ecommerce.inventory.support.PostgresTestContainer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The retry worker with Kafka unreachable: attempts fail and back off, the service stays up and healthy
 * (US-03 FR-014, SC-005).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.bootstrap-servers=localhost:1",
            "spring.kafka.producer.properties.max.block.ms=500",
            "inventory.kafka.publish-timeout=1s",
            "inventory.outbound-retry.enabled=true",
            "inventory.outbound-retry.interval=200ms"
        })
class OutboundRetryWithoutKafkaIT {

    static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainer.instance();

    /**
     * Own database: this context runs the retry worker and stays cached, so it must not see the rows of
     * other test classes.
     *
     * @param registry test properties
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresTestContainer.isolatedDatabase("without_kafka_it"));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    @DisplayName("should record failed attempts and stay healthy while Kafka is unreachable")
    void shouldFailAttemptsAndStayHealthy() {
        DatabaseCleaner.truncateAll(jdbc);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into outbound_dead_letters (id, event_id, topic, message_key, payload, status, retry_count,"
                        + " next_retry_at, last_error, created_at, updated_at)"
                        + " values (?, ?, 'inventory.stock-reserved', 'k', '{}'::jsonb, 'PENDING', 0,"
                        + " now() - interval '1 second', 'broker down', now(), now())",
                id,
                UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20))
                .until(() -> jdbc.queryForObject(
                                "select retry_count from outbound_dead_letters where id = ?", Integer.class, id)
                        >= 1);

        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        assertThat(context.isRunning()).isTrue();
        assertThat(jdbc.queryForObject("select status from outbound_dead_letters where id = ?", String.class, id))
                .isEqualTo("PENDING");
    }
}
