package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
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

    @Autowired
    private ApplicationContext context;

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

    @Test
    @DisplayName("should serve the OpenAPI document of the catalog only")
    void shouldServeOpenApiDocumentOfCatalogOnly() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode paths = new ObjectMapper().readTree(response.getBody()).path("paths");
        List<String> documented = new ArrayList<>();
        paths.fieldNames().forEachRemaining(documented::add);
        assertThat(documented).containsExactlyInAnyOrder("/products", "/products/{id}");
        assertThat(paths.path("/products").has("get")).isTrue();
        assertThat(paths.path("/products").has("post")).isFalse();
    }

    @Test
    @DisplayName("should serve the Swagger UI")
    void shouldServeSwaggerUi() {
        ResponseEntity<String> welcome = restTemplate.getForEntity("/swagger-ui.html", String.class);
        ResponseEntity<String> index = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(welcome.getStatusCode().is2xxSuccessful()
                        || welcome.getStatusCode().is3xxRedirection())
                .isTrue();
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(index.getBody()).contains("Swagger UI");
    }

    @Test
    @DisplayName("should map no HTTP endpoint beyond the permitted surface")
    void shouldMapOnlyPermittedEndpoints() {
        Set<String> mapped = new TreeSet<>();
        context.getBeansOfType(RequestMappingInfoHandlerMapping.class)
                .values()
                .forEach(mapping ->
                        mapping.getHandlerMethods().keySet().forEach(info -> mapped.addAll(info.getPatternValues())));

        // Constitution 1.1.2 Principle II: catalog, OpenAPI document (JSON, YAML, viewer config), Swagger
        // UI welcome page, Actuator health (with liveness/readiness groups) and Boot's internal /error.
        assertThat(mapped)
                .isSubsetOf(
                        "/products",
                        "/products/{id}",
                        "/v3/api-docs",
                        "/v3/api-docs.yaml",
                        "/v3/api-docs/swagger-config",
                        "/swagger-ui.html",
                        "/error",
                        "/actuator",
                        "/actuator/health",
                        "/actuator/health/**");
        assertThat(mapped).contains("/products", "/products/{id}");
        for (String hidden : List.of("/actuator/info", "/actuator/env", "/actuator/beans")) {
            assertThat(restTemplate.getForEntity(hidden, String.class).getStatusCode())
                    .as(hidden)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
