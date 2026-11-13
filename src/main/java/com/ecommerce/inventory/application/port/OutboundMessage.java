package com.ecommerce.inventory.application.port;

import java.util.Objects;
import java.util.UUID;

/**
 * A serialized message ready to be published: the exact bytes go to Kafka and are stored for
 * republishing and dead-lettering.
 *
 * @param eventId envelope event id; kept on every republish
 * @param topic destination topic
 * @param key message key (the aggregate id)
 * @param payload JSON payload
 */
public record OutboundMessage(UUID eventId, String topic, String key, String payload) {

    /** Validates the message. */
    public OutboundMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
    }
}
