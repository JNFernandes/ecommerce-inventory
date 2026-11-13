package com.ecommerce.inventory.messaging;

import com.ecommerce.inventory.messaging.dto.OrderPlacedMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Parses and validates raw {@code orders.order-placed} values (research R5). The reader is strict:
 * quantities must be JSON integers (no {@code "5"}, no {@code 5.5}); unknown fields are ignored.
 */
@Component
public class OrderPlacedMessageParser {

    private final ObjectReader reader;
    private final Validator validator;

    /**
     * Creates the parser.
     *
     * @param validator Bean Validation validator
     */
    public OrderPlacedMessageParser(Validator validator) {
        this.validator = validator;
        this.reader = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
                .readerFor(OrderPlacedMessage.class);
    }

    /**
     * Parses and validates a raw message value.
     *
     * @param raw the message value as received
     * @return the validated message
     * @throws InvalidMessageException when the value is not valid JSON or violates the contract
     */
    public OrderPlacedMessage parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidMessageException("Message value is empty");
        }
        OrderPlacedMessage message;
        try {
            message = reader.readValue(raw);
        } catch (JsonProcessingException e) {
            throw new InvalidMessageException("Message is not a valid order-placed JSON: " + e.getOriginalMessage(), e);
        }
        if (message == null) {
            throw new InvalidMessageException("Message value is JSON null");
        }
        Set<ConstraintViolation<OrderPlacedMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            throw new InvalidMessageException("Message violates the order-placed contract: " + describe(violations));
        }
        return message;
    }

    private static String describe(Set<ConstraintViolation<OrderPlacedMessage>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("; "));
    }
}
