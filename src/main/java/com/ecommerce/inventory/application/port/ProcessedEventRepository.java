package com.ecommerce.inventory.application.port;

import java.util.Optional;
import java.util.UUID;

/** Stores processed inbound event ids. */
public interface ProcessedEventRepository {

    /**
     * Finds a processed event.
     *
     * @param eventId inbound event id
     * @return the record, if the event was already processed
     */
    Optional<ProcessedEvent> find(UUID eventId);

    /**
     * Records a processed event in the current transaction.
     *
     * @param event the record
     */
    void save(ProcessedEvent event);
}
