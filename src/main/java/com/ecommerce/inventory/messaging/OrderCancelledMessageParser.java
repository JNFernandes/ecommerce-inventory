package com.ecommerce.inventory.messaging;

import com.ecommerce.inventory.messaging.dto.OrderCancelledMessage;
import org.springframework.stereotype.Component;

/** Parses and validates raw {@code orders.order-cancelled} values with the shared {@link StrictJsonParser}. */
@Component
public class OrderCancelledMessageParser {

    private final StrictJsonParser parser;

    /**
     * Creates the parser.
     *
     * @param parser shared strict parser
     */
    public OrderCancelledMessageParser(StrictJsonParser parser) {
        this.parser = parser;
    }

    /**
     * Parses and validates a raw message value.
     *
     * @param raw the message value as received
     * @return the validated message
     * @throws InvalidMessageException when the value is not valid JSON or violates the contract
     */
    public OrderCancelledMessage parse(String raw) {
        return parser.parse(raw, OrderCancelledMessage.class, "order-cancelled");
    }
}
