package com.ecommerce.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.messaging.dto.OrderPlacedMessage;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderPlacedMessageParserTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    private final OrderPlacedMessageParser parser = new OrderPlacedMessageParser(VALIDATORS.getValidator());

    @AfterAll
    static void close() {
        VALIDATORS.close();
    }

    @Test
    @DisplayName("should parse the contract example")
    void shouldParseContractExample() {
        OrderPlacedMessage message = parser.parse(example("3", "{\"productId\":\"SKU-002\",\"quantity\":5}"));

        assertThat(message.eventId()).isEqualTo(UUID.fromString("8f0c6a1e-6a4b-4a43-9a1c-1f5f7f0a2b10"));
        assertThat(message.aggregateId()).isEqualTo(UUID.fromString("3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"));
        assertThat(message.version()).isEqualTo(1);
        assertThat(message.items()).hasSize(2);
        assertThat(message.items().getFirst().productId()).isEqualTo("SKU-001");
        assertThat(message.items().getFirst().quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("should ignore customerId, unitPrice, totalAmount and unknown fields")
    void shouldIgnoreUnusedAndUnknownFields() {
        String raw = example("3", "{\"productId\":\"SKU-002\",\"quantity\":5,\"color\":\"red\"}")
                .replace("\"version\":1,", "\"version\":1,\"channel\":\"web\",");

        assertThat(parser.parse(raw).items()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json", "null", "[]", "{\"not\":\"an order\"}"})
    @DisplayName("should reject values that are not an order-placed JSON object")
    void shouldRejectNonOrderValues(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    @DisplayName("should reject a message without eventId")
    void shouldRejectMissingEventId() {
        String raw = example("3", null).replace("\"eventId\":\"8f0c6a1e-6a4b-4a43-9a1c-1f5f7f0a2b10\",", "");

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("should reject a message with no items")
    void shouldRejectEmptyItems() {
        String raw = """
                {"eventId":"8f0c6a1e-6a4b-4a43-9a1c-1f5f7f0a2b10","occurredAt":"2026-11-13T10:15:30.123Z",
                 "aggregateId":"3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","version":1,"items":[]}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("items");
    }

    @Test
    @DisplayName("should reject a blank product id")
    void shouldRejectBlankProductId() {
        String raw = example("3", "{\"productId\":\" \",\"quantity\":5}");

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("productId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "\"5\"", "5.5", "5.0", "null"})
    @DisplayName("should reject a quantity that is not a positive JSON integer")
    void shouldRejectInvalidQuantity(String quantity) {
        assertThatThrownBy(() -> parser.parse(example(quantity, null))).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    @DisplayName("should reject an unsupported version")
    void shouldRejectUnsupportedVersion() {
        String raw = example("3", null).replace("\"version\":1", "\"version\":2");

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("version must be 1");
    }

    private static String example(String firstQuantity, String extraItem) {
        String items = "{\"productId\":\"SKU-001\",\"quantity\":" + firstQuantity + ",\"unitPrice\":19.99}"
                + (extraItem == null ? "" : "," + extraItem);
        return "{\"eventId\":\"8f0c6a1e-6a4b-4a43-9a1c-1f5f7f0a2b10\","
                + "\"occurredAt\":\"2026-11-13T10:15:30.123Z\","
                + "\"aggregateId\":\"3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d\","
                + "\"version\":1,"
                + "\"customerId\":\"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d\","
                + "\"items\":[" + items + "],"
                + "\"totalAmount\":82.47}";
    }
}
