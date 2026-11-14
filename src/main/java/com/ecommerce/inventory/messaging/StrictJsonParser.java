package com.ecommerce.inventory.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Parses and validates every consumed message the same strict way (US-01 research R5): numbers must
 * be JSON integers where integers are expected (no {@code "5"}, no {@code 5.5}), unknown fields are
 * ignored, and every Bean Validation constraint must hold. Any failure is an
 * {@link InvalidMessageException}, which is never retried.
 */
@Component
public class StrictJsonParser {

    private final JsonMapper mapper;
    private final Validator validator;

    /**
     * Creates the parser.
     *
     * @param validator Bean Validation validator
     */
    public StrictJsonParser(Validator validator) {
        this.validator = validator;
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Parses and validates a raw message value.
     *
     * @param raw the message value as received
     * @param type the record DTO to parse into
     * @param contractName contract name used in error messages, for example {@code order-placed}
     * @param <T> the DTO type
     * @return the validated message
     * @throws InvalidMessageException when the value is not valid JSON or violates the contract
     */
    public <T> T parse(String raw, Class<T> type, String contractName) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidMessageException("Message value is empty");
        }
        T message;
        try {
            message = mapper.readerFor(type).readValue(raw);
        } catch (JsonProcessingException e) {
            throw new InvalidMessageException(
                    "Message is not a valid " + contractName + " JSON: " + e.getOriginalMessage(), e);
        }
        if (message == null) {
            throw new InvalidMessageException("Message value is JSON null");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            throw new InvalidMessageException(
                    "Message violates the " + contractName + " contract: " + describe(violations));
        }
        return message;
    }

    private static <T> String describe(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("; "));
    }
}
