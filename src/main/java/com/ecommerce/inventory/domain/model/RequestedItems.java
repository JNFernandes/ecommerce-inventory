package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.exception.InvalidQuantityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The consolidated items of an order: one entry per product, sorted by product id, never empty.
 *
 * @param items the items, one per product, sorted by product id
 */
public record RequestedItems(List<RequestedItem> items) {

    /** Validates that the items are non-empty, sorted and unique per product. */
    public RequestedItems {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new InvalidQuantityException("An order must contain at least one item");
        }
        items = items.stream()
                .sorted(Comparator.comparing(RequestedItem::productId))
                .toList();
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i - 1).productId().equals(items.get(i).productId())) {
                throw new IllegalArgumentException(
                        "Duplicate product " + items.get(i).productId() + "; use RequestedItems.consolidate");
            }
        }
    }

    /**
     * Consolidates order lines: lines for the same product are merged by summing their quantities
     * (FR-004).
     *
     * @param lines the order lines, possibly with repeated products
     * @return the consolidated items
     */
    public static RequestedItems consolidate(List<RequestedItem> lines) {
        Objects.requireNonNull(lines, "lines");
        Map<ProductId, Quantity> totals = new TreeMap<>();
        for (RequestedItem line : lines) {
            totals.merge(line.productId(), line.quantity(), Quantity::plus);
        }
        List<RequestedItem> consolidated = new ArrayList<>();
        totals.forEach((productId, quantity) -> consolidated.add(new RequestedItem(productId, quantity)));
        return new RequestedItems(consolidated);
    }

    /**
     * The product ids, in ascending order.
     *
     * @return the product ids
     */
    public SortedSet<ProductId> productIds() {
        SortedSet<ProductId> ids = new TreeSet<>();
        items.forEach(item -> ids.add(item.productId()));
        return Collections.unmodifiableSortedSet(ids);
    }
}
