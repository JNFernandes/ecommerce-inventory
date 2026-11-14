package com.ecommerce.inventory.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.application.OutboundRetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Binding and validation of {@code inventory.outbound-retry.*} (US-03 FR-016). */
class OutboundRetryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class, ApplicationPolicyConfiguration.class);

    @Test
    @DisplayName("should bind the documented defaults from application.yml")
    void shouldBindDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            InventoryProperties.OutboundRetry retry =
                    context.getBean(InventoryProperties.class).outboundRetry();
            assertThat(retry.enabled()).isTrue();
            assertThat(retry.interval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(context.getBean(OutboundRetryPolicy.class))
                    .isEqualTo(new OutboundRetryPolicy(50, 10, Duration.ofSeconds(30), 2.0, Duration.ofHours(1)));
        });
    }

    @Test
    @DisplayName("should apply environment variable overrides")
    void shouldApplyEnvironmentOverrides() {
        runner.withPropertyValues(
                        "OUTBOUND_RETRY_ENABLED=false",
                        "OUTBOUND_RETRY_INTERVAL=5s",
                        "OUTBOUND_RETRY_BATCH_SIZE=7",
                        "OUTBOUND_RETRY_MAX_ATTEMPTS=4",
                        "OUTBOUND_RETRY_BASE_DELAY=2s",
                        "OUTBOUND_RETRY_MULTIPLIER=3.0",
                        "OUTBOUND_RETRY_MAX_DELAY=1m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    InventoryProperties.OutboundRetry retry =
                            context.getBean(InventoryProperties.class).outboundRetry();
                    assertThat(retry.enabled()).isFalse();
                    assertThat(retry.interval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(context.getBean(OutboundRetryPolicy.class))
                            .isEqualTo(
                                    new OutboundRetryPolicy(7, 4, Duration.ofSeconds(2), 3.0, Duration.ofMinutes(1)));
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "inventory.outbound-retry.max-attempts=0",
                "inventory.outbound-retry.batch-size=0",
                "inventory.outbound-retry.multiplier=0.5",
                "inventory.outbound-retry.max-delay=1s"
            })
    @DisplayName("should refuse to start with an invalid setting")
    void shouldRefuseInvalidSettings(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InventoryProperties.class)
    static class PropertiesConfiguration {}
}
