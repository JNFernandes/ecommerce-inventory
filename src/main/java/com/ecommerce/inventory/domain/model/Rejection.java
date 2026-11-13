package com.ecommerce.inventory.domain.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Why a reservation was rejected.
 *
 * @param reason the reason
 * @param failures failing items only, sorted by product id; never empty
 */
public record Rejection(RejectionReason reason, List<ItemFailure> failures) {

    /** Validates the rejection and sorts the failures. */
    public Rejection {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(failures, "failures");
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("A rejection needs at least one failing item");
        }
        failures = failures.stream()
                .sorted(Comparator.comparing(ItemFailure::productId))
                .toList();
    }
}
