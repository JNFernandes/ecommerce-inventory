package com.ecommerce.inventory.domain.model;

import java.util.Set;

/** Reason of a rejected reservation; exactly one of three values (FR-008). */
public enum RejectionReason {
    /** Only insufficient items. */
    INSUFFICIENT_STOCK("insufficient stock"),
    /** Only unknown products. */
    UNKNOWN_PRODUCT("unknown product"),
    /** Both unknown products and insufficient items. */
    UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK("unknown product and insufficient stock");

    private final String wireValue;

    RejectionReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The exact text published in {@code inventory.stock-rejected}.
     *
     * @return the reason text
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Picks the reason for the kinds of failures found.
     *
     * @param kinds kinds of failing items; must not be empty
     * @return the matching reason
     */
    public static RejectionReason from(Set<FailureKind> kinds) {
        boolean unknown = kinds.contains(FailureKind.UNKNOWN_PRODUCT);
        boolean insufficient = kinds.contains(FailureKind.INSUFFICIENT_STOCK);
        if (unknown && insufficient) {
            return UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK;
        }
        if (unknown) {
            return UNKNOWN_PRODUCT;
        }
        if (insufficient) {
            return INSUFFICIENT_STOCK;
        }
        throw new IllegalArgumentException("A rejection needs at least one failure");
    }
}
