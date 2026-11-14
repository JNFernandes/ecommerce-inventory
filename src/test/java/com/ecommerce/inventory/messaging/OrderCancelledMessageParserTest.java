package com.ecommerce.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.messaging.dto.OrderCancelledMessage;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderCancelledMessageParserTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final String EVENT_ID = "2c9d8e7f-6a5b-4c3d-9e8f-7a6b5c4d3e2f";
    private static final String ORDER_ID = "3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

    private final OrderCancelledMessageParser parser =
            new OrderCancelledMessageParser(new StrictJsonParser(VALIDATORS.getValidator()));

    @AfterAll
    static void close() {
        VALIDATORS.close();
    }

    @Test
    @DisplayName("should parse the contract example")
    void shouldParseContractExample() {
        OrderCancelledMessage message = parser.parse(example());

        assertThat(message.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(message.aggregateId()).isEqualTo(UUID.fromString(ORDER_ID));
        assertThat(message.version()).isEqualTo(1);
        assertThat(message.reason()).isEqualTo("payment failed");
    }

    @Test
    @DisplayName("should accept a cancellation without reason and customerId, ignoring unknown fields")
    void shouldAcceptOptionalFieldsMissing() {
        String raw = "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-11-13T10:20:00.000Z\","
                + "\"aggregateId\":\"" + ORDER_ID + "\",\"version\":1,\"channel\":\"web\"}";

        OrderCancelledMessage message = parser.parse(raw);

        assertThat(message.reason()).isNull();
        assertThat(message.customerId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "null", "{\"not\":\"a cancellation\"}"})
    @DisplayName("should reject values that are not an order-cancelled JSON object")
    void shouldRejectNonCancellations(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    @DisplayName("should reject a cancellation without eventId or aggregateId")
    void shouldRejectMissingIds() {
        assertThatThrownBy(() -> parser.parse(example().replace("\"eventId\":\"" + EVENT_ID + "\",", "")))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> parser.parse(example().replace("\"aggregateId\":\"" + ORDER_ID + "\",", "")))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    @DisplayName("should reject an unsupported version")
    void shouldRejectUnsupportedVersion() {
        assertThatThrownBy(() -> parser.parse(example().replace("\"version\":1", "\"version\":2")))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("version must be 1");
    }

    private static String example() {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-11-13T10:20:00.000Z\","
                + "\"aggregateId\":\"" + ORDER_ID + "\",\"version\":1,"
                + "\"customerId\":\"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d\",\"reason\":\"payment failed\"}";
    }
}
