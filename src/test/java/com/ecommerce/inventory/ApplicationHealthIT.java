package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.support.PostgresTestContainer;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Smoke test: the full application boots against a real PostgreSQL and reports healthy.
 *
 * <p>Kafka deliberately points at a port where nothing listens, proving the service starts and
 * stays healthy while the broker is unreachable (constitution Principle VIII), and that the read-only
 * catalog answers without it (US-04 FR-021).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.bootstrap-servers=localhost:1", "inventory.outbound-retry.enabled=false"})
class ApplicationHealthIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainer.instance();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("should report UP on /actuator/health when PostgreSQL is up and Kafka is unreachable")
    void shouldReportHealthUpWithoutKafka() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("should expose only the health endpoint through actuator")
    void shouldNotExposeOtherActuatorEndpoints() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/env", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("should serve the catalog with Kafka unreachable")
    void shouldServeCatalogWithoutKafka() {
        ResponseEntity<String> list = restTemplate.getForEntity("/products", String.class);
        ResponseEntity<String> missing = restTemplate.getForEntity("/products/SKU-404", String.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains("\"items\"", "\"total\"", "\"limit\":50", "\"offset\":0");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(missing.getBody()).contains("\"productId\":\"SKU-404\"");
    }

    @Test
    @DisplayName("should look up an id with an encoded space")
    void shouldDecodeEncodedSpace() {
        ResponseEntity<String> response = restTemplate.getForEntity(URI.create("/products/SKU%20X"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"productId\":\"SKU X\"");
    }

    @Test
    @DisplayName("should refuse an encoded slash without a server error")
    void shouldRefuseEncodedSlash() {
        ResponseEntity<String> response = restTemplate.getForEntity(URI.create("/products/a%2Fb"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
