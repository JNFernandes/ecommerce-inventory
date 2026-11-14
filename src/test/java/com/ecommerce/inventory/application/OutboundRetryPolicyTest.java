package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundRetryPolicyTest {

    private final OutboundRetryPolicy policy =
            new OutboundRetryPolicy(50, 10, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(15));

    @Test
    @DisplayName("should double the delay after each failure and cap it at the maximum")
    void shouldBackOffExponentiallyWithCap() {
        assertThat(IntStream.rangeClosed(1, 7).mapToObj(policy::delayAfter))
                .containsExactly(
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(4),
                        Duration.ofMinutes(8),
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("should stay at the cap for huge attempt numbers without overflow")
    void shouldNotOverflow() {
        assertThat(policy.delayAfter(10_000)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("should keep a constant delay with multiplier 1")
    void shouldSupportConstantDelay() {
        OutboundRetryPolicy constant = new OutboundRetryPolicy(1, 3, Duration.ofSeconds(5), 1.0, Duration.ofSeconds(5));

        assertThat(constant.delayAfter(1)).isEqualTo(constant.delayAfter(3)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("should exhaust exactly at the maximum number of attempts")
    void shouldExhaustAtMaxAttempts() {
        assertThat(policy.isExhausted(9)).isFalse();
        assertThat(policy.isExhausted(10)).isTrue();
    }

    @Test
    @DisplayName("should refuse invalid settings")
    void shouldRefuseInvalidSettings() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new OutboundRetryPolicy(0, 10, second, 2.0, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size");
        assertThatThrownBy(() -> new OutboundRetryPolicy(50, 0, second, 2.0, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max attempts");
        assertThatThrownBy(() -> new OutboundRetryPolicy(50, 10, Duration.ZERO, 2.0, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base delay");
        assertThatThrownBy(() -> new OutboundRetryPolicy(50, 10, second, 0.5, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
        assertThatThrownBy(() -> new OutboundRetryPolicy(50, 10, Duration.ofSeconds(30), 2.0, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max delay");
    }

    @Test
    @DisplayName("should refuse an attempt number below 1")
    void shouldRefuseAttemptZero() {
        assertThatThrownBy(() -> policy.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
