package com.ecommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.exception.InsufficientStockException;
import com.ecommerce.inventory.domain.exception.InvalidQuantityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockItemTest {

    private static final ProductId SKU = new ProductId("SKU-001");

    @Test
    @DisplayName("should supply and reserve when enough stock is available")
    void shouldReserveWhenAvailable() {
        StockItem stock = new StockItem(SKU, 10);

        assertThat(stock.canSupply(new Quantity(3))).isTrue();
        stock.reserve(new Quantity(3));

        assertThat(stock.availableQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("should allow reserving the exact available quantity, reaching zero")
    void shouldAllowReachingZero() {
        StockItem stock = new StockItem(SKU, 5);

        stock.reserve(new Quantity(5));

        assertThat(stock.availableQuantity()).isZero();
    }

    @Test
    @DisplayName("should throw and keep the quantity unchanged when more than available is reserved")
    void shouldRefuseToGoNegative() {
        StockItem stock = new StockItem(SKU, 5);

        assertThat(stock.canSupply(new Quantity(6))).isFalse();
        assertThatThrownBy(() -> stock.reserve(new Quantity(6)))
                .isInstanceOfSatisfying(InsufficientStockException.class, e -> {
                    assertThat(e.productId()).isEqualTo("SKU-001");
                    assertThat(e.requested()).isEqualTo(6);
                    assertThat(e.available()).isEqualTo(5);
                });
        assertThat(stock.availableQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("should not supply a quantity beyond the int range")
    void shouldNotSupplyHugeQuantity() {
        StockItem stock = new StockItem(SKU, Integer.MAX_VALUE);

        assertThat(stock.canSupply(new Quantity(Integer.MAX_VALUE + 1L))).isFalse();
    }

    @Test
    @DisplayName("should refuse a negative initial quantity")
    void shouldRefuseNegativeInitialQuantity() {
        assertThatThrownBy(() -> new StockItem(SKU, -1)).isInstanceOf(InvalidQuantityException.class);
    }
}
