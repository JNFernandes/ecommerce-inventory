package com.ecommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.exception.InvalidQuantityException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestedItemsTest {

    @Test
    @DisplayName("should merge lines for the same product by summing their quantities")
    void shouldMergeDuplicateProducts() {
        RequestedItems items = RequestedItems.consolidate(List.of(item("SKU-001", 2), item("SKU-001", 3)));

        assertThat(items.items()).containsExactly(item("SKU-001", 5));
    }

    @Test
    @DisplayName("should sort items by product id and keep distinct products")
    void shouldSortAndKeepDistinctProducts() {
        RequestedItems items =
                RequestedItems.consolidate(List.of(item("SKU-002", 5), item("SKU-001", 3), item("SKU-002", 1)));

        assertThat(items.items()).containsExactly(item("SKU-001", 3), item("SKU-002", 6));
        assertThat(items.productIds()).containsExactly(new ProductId("SKU-001"), new ProductId("SKU-002"));
    }

    @Test
    @DisplayName("should refuse an order without items")
    void shouldRefuseEmptyList() {
        assertThatThrownBy(() -> RequestedItems.consolidate(List.of())).isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    @DisplayName("should consolidate quantities whose sum exceeds Integer.MAX_VALUE without error")
    void shouldConsolidateBeyondIntRange() {
        RequestedItems items = RequestedItems.consolidate(
                List.of(item("SKU-001", Integer.MAX_VALUE), item("SKU-001", Integer.MAX_VALUE)));

        assertThat(items.items().getFirst().quantity().value()).isEqualTo(2L * Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("should refuse unconsolidated duplicates in the canonical constructor")
    void shouldRefuseDuplicatesInConstructor() {
        assertThatThrownBy(() -> new RequestedItems(List.of(item("SKU-001", 1), item("SKU-001", 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RequestedItem item(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
    }
}
