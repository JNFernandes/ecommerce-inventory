package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.infrastructure.persistence.ConsumedDeadLetterRepository;
import com.ecommerce.inventory.infrastructure.persistence.ConsumedDeadLetterRepository.ConsumedRecord;
import com.ecommerce.inventory.messaging.InvalidMessageException;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Last step for a consumed message that cannot be processed: stores it in the consumed-message
 * dead-letter table and logs it with full context. The error handler then commits its offset so the
 * partition is not blocked (FR-024). If storing fails, the exception propagates and the record is
 * retried, so it is never lost.
 */
public final class ConsumedDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumedDeadLetterRecoverer.class);

    private final ConsumedDeadLetterRepository repository;
    private final int maxRetries;

    /**
     * Creates the recoverer.
     *
     * @param repository consumed-message dead-letter store
     * @param maxRetries configured retries after the first attempt, for the attempt count
     */
    public ConsumedDeadLetterRecoverer(ConsumedDeadLetterRepository repository, int maxRetries) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.maxRetries = maxRetries;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> consumerRecord, Exception exception) {
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(exception);
        int attempts = isInvalidMessage(exception) ? 1 : maxRetries + 1;
        String error = rootCause.getClass().getName() + ": " + rootCause.getMessage();
        repository.record(
                new ConsumedRecord(
                        consumerRecord.topic(),
                        consumerRecord.partition(),
                        consumerRecord.offset(),
                        text(consumerRecord.key()),
                        text(consumerRecord.value())),
                error,
                attempts);
        LOG.error(
                "Dead-lettered {}-{}@{} (key {}) after {} attempt(s): {}",
                consumerRecord.topic(),
                consumerRecord.partition(),
                consumerRecord.offset(),
                consumerRecord.key(),
                attempts,
                error,
                exception);
    }

    private static boolean isInvalidMessage(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof InvalidMessageException) {
                return true;
            }
        }
        return false;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
