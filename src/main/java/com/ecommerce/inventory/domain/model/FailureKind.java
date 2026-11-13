package com.ecommerce.inventory.domain.model;

/** Why one item of an order could not be reserved. */
public enum FailureKind {
    /** No stock item exists for the product. */
    UNKNOWN_PRODUCT,
    /** The product exists but fewer units are available than requested. */
    INSUFFICIENT_STOCK
}
