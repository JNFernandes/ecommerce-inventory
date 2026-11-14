package com.ecommerce.inventory.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundDeadLetterTest {

    private static final OutboundMessage MESSAGE =
            new OutboundMessage(UUID.randomUUID(), "inventory.stock-reserved", "key", "{}");

    @Test
    @DisplayName("should keep the id, message and retry count of a claimed row")
    void shouldHoldClaimedRow() {
        UUID id = UUID.randomUUID();

        OutboundDeadLetter row = new OutboundDeadLetter(id, MESSAGE, 3);

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.message()).isEqualTo(MESSAGE);
        assertThat(row.retryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("should refuse a negative retry count or a missing id or message")
    void shouldRefuseInvalidRow() {
        assertThatThrownBy(() -> new OutboundDeadLetter(UUID.randomUUID(), MESSAGE, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundDeadLetter(null, MESSAGE, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboundDeadLetter(UUID.randomUUID(), null, 0))
                .isInstanceOf(NullPointerException.class);
    }
}
