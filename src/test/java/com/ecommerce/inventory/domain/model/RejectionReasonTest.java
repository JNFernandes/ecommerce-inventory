package com.ecommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.exception.InvalidQuantityException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RejectionReasonTest {

    @Test
    @DisplayName("should map each non-empty set of failure kinds to its reason (FR-008)")
    void shouldMapFailureKinds() {
        assertThat(RejectionReason.from(EnumSet.of(FailureKind.INSUFFICIENT_STOCK)))
                .isEqualTo(RejectionReason.INSUFFICIENT_STOCK);
        assertThat(RejectionReason.from(EnumSet.of(FailureKind.UNKNOWN_PRODUCT)))
                .isEqualTo(RejectionReason.UNKNOWN_PRODUCT);
        assertThat(RejectionReason.from(EnumSet.allOf(FailureKind.class)))
                .isEqualTo(RejectionReason.UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("should refuse an empty set of failure kinds")
    void shouldRefuseEmptySet() {
        assertThatThrownBy(() -> RejectionReason.from(Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should publish the exact reason texts from the contract")
    void shouldExposeExactWireValues() {
        assertThat(RejectionReason.INSUFFICIENT_STOCK.wireValue()).isEqualTo("insufficient stock");
        assertThat(RejectionReason.UNKNOWN_PRODUCT.wireValue()).isEqualTo("unknown product");
        assertThat(RejectionReason.UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK.wireValue())
                .isEqualTo("unknown product and insufficient stock");
    }

    @Test
    @DisplayName("should require available 0 for an unknown product and valid quantities for a failure")
    void shouldValidateItemFailure() {
        ProductId sku = new ProductId("SKU-999");

        assertThatThrownBy(() -> new ItemFailure(sku, 1, 3, FailureKind.UNKNOWN_PRODUCT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemFailure(sku, 0, 0, FailureKind.UNKNOWN_PRODUCT))
                .isInstanceOf(InvalidQuantityException.class);
        assertThatThrownBy(() -> new ItemFailure(sku, 1, -1, FailureKind.INSUFFICIENT_STOCK))
                .isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    @DisplayName("should sort the failures of a rejection by product id and refuse an empty list")
    void shouldSortRejectionFailures() {
        ItemFailure b = new ItemFailure(new ProductId("SKU-B"), 1, 0, FailureKind.UNKNOWN_PRODUCT);
        ItemFailure a = new ItemFailure(new ProductId("SKU-A"), 2, 1, FailureKind.INSUFFICIENT_STOCK);

        assertThat(new Rejection(RejectionReason.UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK, List.of(b, a)).failures())
                .containsExactly(a, b);
        assertThatThrownBy(() -> new Rejection(RejectionReason.UNKNOWN_PRODUCT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
