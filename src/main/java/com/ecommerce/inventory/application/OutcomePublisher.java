package com.ecommerce.inventory.application;

import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.application.port.PublishFailedException;
import com.ecommerce.inventory.domain.model.OrderId;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only way outcome messages leave the service, used after the database commit by every flow
 * (reserve and release). A failure never undoes the committed state: the message is stored as an
 * outbound dead letter in its own transaction for the retry worker (US-01 FR-023, US-02 FR-019,
 * constitution v1.0.1 Principle III). If even that fails, the full message is logged for manual
 * recovery.
 */
@Component
public class OutcomePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutcomePublisher.class);

    private final OutboundMessagePublisher publisher;
    private final OutboundDeadLetterRepository outboundDeadLetters;
    private final DeadLetterPolicy deadLetterPolicy;
    private final Clock clock;

    /**
     * Creates the publisher.
     *
     * @param publisher publishes outbound messages
     * @param outboundDeadLetters stores messages whose publish failed
     * @param deadLetterPolicy when a failed message may be retried
     * @param clock source of the retry time
     */
    public OutcomePublisher(
            OutboundMessagePublisher publisher,
            OutboundDeadLetterRepository outboundDeadLetters,
            DeadLetterPolicy deadLetterPolicy,
            Clock clock) {
        this.publisher = publisher;
        this.outboundDeadLetters = outboundDeadLetters;
        this.deadLetterPolicy = deadLetterPolicy;
        this.clock = clock;
    }

    /**
     * Publishes an outcome message; on failure stores it as an outbound dead letter. Never throws for a
     * publish failure.
     *
     * @param message the message to publish
     * @param orderId order the message is about, for the log
     */
    public void publish(OutboundMessage message, OrderId orderId) {
        try {
            publisher.publish(message);
        } catch (PublishFailedException e) {
            LOG.error(
                    "Publishing event {} to {} with key {} for order {} failed; storing it as an outbound dead letter",
                    message.eventId(),
                    message.topic(),
                    message.key(),
                    orderId,
                    e);
            try {
                outboundDeadLetters.recordFailure(
                        message, describe(e), clock.instant().plus(deadLetterPolicy.firstRetryDelay()));
            } catch (RuntimeException storeFailure) {
                LOG.error(
                        "Could not dead-letter event {}; recover manually: topic={} key={} payload={}",
                        message.eventId(),
                        message.topic(),
                        message.key(),
                        message.payload(),
                        storeFailure);
            }
        }
    }

    private static String describe(Exception failure) {
        StringBuilder description = new StringBuilder(failure.toString());
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            description.append(" | caused by ").append(cause);
        }
        return description.toString();
    }
}
