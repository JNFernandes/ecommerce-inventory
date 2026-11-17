package com.ecommerce.inventory.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.inventory.support.AbstractComponentIT;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The read-only catalog follows reservations and releases end to end (US-04 SC-003): events on real
 * Kafka change the stock, and {@code GET /products} over real HTTP shows it.
 */
class ProductCatalogComponentIT extends AbstractComponentIT {

    private static final String ORDER_PLACED = "orders.order-placed";
    private static final String ORDER_CANCELLED = "orders.order-cancelled";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    @DisplayName("should show reserved stock as unavailable and released stock as available again")
    void shouldFollowReservationAndRelease() {
        stock().given("SKU-CAT", "Catalog Item", 10);
        assertThat(availableQuantityFromApi("SKU-CAT")).isEqualTo(10);
        assertThat(httpGet("/products/SKU-CAT").getBody()).contains("\"name\":\"Catalog Item\"");

        UUID orderId = UUID.randomUUID();
        kafka().send(ORDER_PLACED, orderId.toString(), orderPlaced(UUID.randomUUID(), orderId, line("SKU-CAT", 2)));

        await().atMost(TIMEOUT)
                .untilAsserted(
                        () -> assertThat(availableQuantityFromApi("SKU-CAT")).isEqualTo(8));

        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(UUID.randomUUID(), orderId));

        await().atMost(TIMEOUT)
                .untilAsserted(
                        () -> assertThat(availableQuantityFromApi("SKU-CAT")).isEqualTo(10));

        ResponseEntity<String> list = httpGet("/products");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody())
                .contains("{\"id\":\"SKU-CAT\",\"name\":\"Catalog Item\",\"availableQuantity\":10}")
                .contains("\"total\":1");
    }
}
