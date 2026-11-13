package com.ecommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.exception.InvalidProductIdException;
import com.ecommerce.inventory.domain.exception.InvalidQuantityException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ValueObjectsTest {

    @Test
    @DisplayName("should accept a product id of up to 64 characters")
    void shouldAcceptValidProductId() {
        String max = "P".repeat(ProductId.MAX_LENGTH);

        assertThat(new ProductId("SKU-001").value()).isEqualTo("SKU-001");
        assertThat(new ProductId(max).value()).hasSize(64);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("should refuse a missing or blank product id")
    void shouldRefuseBlankProductId(String value) {
        assertThatThrownBy(() -> new ProductId(value)).isInstanceOf(InvalidProductIdException.class);
    }

    @Test
    @DisplayName("should refuse a product id longer than 64 characters")
    void shouldRefuseLongProductId() {
        assertThatThrownBy(() -> new ProductId("P".repeat(65))).isInstanceOf(InvalidProductIdException.class);
    }

    @Test
    @DisplayName("should compare product ids exactly, case-sensitive")
    void shouldCompareProductIdsCaseSensitively() {
        assertThat(new ProductId("sku-001")).isNotEqualTo(new ProductId("SKU-001"));
        assertThat(new ProductId("SKU-001").compareTo(new ProductId("SKU-002"))).isNegative();
    }

    @Test
    @DisplayName("should refuse null order and reservation ids")
    void shouldRefuseNullIds() {
        assertThatThrownBy(() -> new OrderId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should generate distinct reservation ids")
    void shouldGenerateDistinctReservationIds() {
        assertThat(ReservationId.newId()).isNotEqualTo(ReservationId.newId());
        assertThat(new OrderId(UUID.fromString("3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")))
                .hasToString("3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    @DisplayName("should refuse a zero or negative quantity")
    void shouldRefuseNonPositiveQuantity(long value) {
        assertThatThrownBy(() -> new Quantity(value)).isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    @DisplayName("should add two maximal int quantities without overflow")
    void shouldAddLargeQuantitiesExactly() {
        Quantity max = new Quantity(Integer.MAX_VALUE);

        assertThat(max.plus(max).value()).isEqualTo(2L * Integer.MAX_VALUE);
    }
}
