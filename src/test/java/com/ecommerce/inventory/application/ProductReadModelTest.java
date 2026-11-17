package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductReadModelTest {

    private static final ProductView MOUSE = new ProductView("SKU-001", "Wireless Mouse", 10);

    @Test
    @DisplayName("should hold the id, name and available quantity of a product")
    void shouldCreateView() {
        ProductView view = new ProductView("SKU-005", "Webcam HD", 0);

        assertThat(view.productId()).isEqualTo("SKU-005");
        assertThat(view.name()).isEqualTo("Webcam HD");
        assertThat(view.availableQuantity()).isZero();
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"NULL, Mouse, 1", "' ', Mouse, 1", "SKU-1, NULL, 1", "SKU-1, ' ', 1", "SKU-1, Mouse, -1"})
    @DisplayName("should reject a blank id or name and a negative quantity")
    void shouldRejectInvalidView(String productId, String name, int availableQuantity) {
        assertThatThrownBy(() -> new ProductView(productId, name, availableQuantity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should hold the items, total, limit and offset of a page")
    void shouldCreatePage() {
        ProductPage page = new ProductPage(List.of(MOUSE), 5, 2, 3);

        assertThat(page.items()).containsExactly(MOUSE);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.offset()).isEqualTo(3);
    }

    @Test
    @DisplayName("should accept an empty page past the end")
    void shouldAcceptEmptyPage() {
        assertThat(new ProductPage(List.of(), 5, 50, 10).items()).isEmpty();
    }

    @Test
    @DisplayName("should copy the items so the page cannot change afterwards")
    void shouldCopyItems() {
        List<ProductView> items = new ArrayList<>(List.of(MOUSE));
        ProductPage page = new ProductPage(items, 1, 50, 0);

        items.clear();

        assertThat(page.items()).containsExactly(MOUSE);
        assertThatThrownBy(() -> page.items().add(MOUSE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1, 50, 0", "1, 0, 0", "1, 201, 0", "1, 50, -1"})
    @DisplayName("should reject a negative total, a limit outside 1..200 and a negative offset")
    void shouldRejectInvalidPage(long total, int limit, int offset) {
        assertThatThrownBy(() -> new ProductPage(List.of(), total, limit, offset))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a page with more items than its limit")
    void shouldRejectOverfullPage() {
        assertThatThrownBy(() -> new ProductPage(List.of(MOUSE, MOUSE), 2, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should name the missing product id")
    void shouldNameMissingProduct() {
        ProductNotFoundException exception = new ProductNotFoundException("SKU-999");

        assertThat(exception.productId()).isEqualTo("SKU-999");
        assertThat(exception).hasMessage("Product SKU-999 not found");
    }
}
