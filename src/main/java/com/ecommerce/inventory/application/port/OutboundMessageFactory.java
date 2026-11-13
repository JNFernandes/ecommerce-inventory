package com.ecommerce.inventory.application.port;

import com.ecommerce.inventory.domain.event.InventoryDomainEvent;

/** Turns a domain event into the message published for it (topic, key and JSON contract). */
public interface OutboundMessageFactory {

    /**
     * Builds the outbound message for a domain event, with a new event id.
     *
     * @param event domain event
     * @return the message to publish
     */
    OutboundMessage create(InventoryDomainEvent event);
}
