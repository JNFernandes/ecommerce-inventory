package com.ecommerce.inventory.application.port;

/** Publishes outbound messages. Called only after the database transaction has committed. */
public interface OutboundMessagePublisher {

    /**
     * Publishes the message and waits for the broker acknowledgement.
     *
     * @param message message to publish
     * @throws PublishFailedException when the broker did not acknowledge the message in time
     */
    void publish(OutboundMessage message);
}
