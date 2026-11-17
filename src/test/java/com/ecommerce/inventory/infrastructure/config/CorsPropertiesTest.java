package com.ecommerce.inventory.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/**
 * {@code CORS_ALLOWED_ORIGINS} binding (US-04 FR-023). Every test sets the variable explicitly, so a
 * value left in the developer's shell can never change the result.
 */
class CorsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("should allow no origin by default")
    void shouldAllowNoOriginByDefault() {
        runner.withPropertyValues("CORS_ALLOWED_ORIGINS=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(origins(context.getBean(InventoryProperties.class))).isEmpty();
        });
    }

    @Test
    @DisplayName("should split, trim and drop blank origins")
    void shouldSplitAndTrimOrigins() {
        runner.withPropertyValues("CORS_ALLOWED_ORIGINS= http://a.test , ,https://b.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(origins(context.getBean(InventoryProperties.class)))
                            .containsExactly("http://a.test", "https://b.test");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example", "http://ok.test,*"})
    @DisplayName("should fail startup for a wildcard origin")
    void shouldRefuseWildcards(String origins) {
        runner.withPropertyValues("CORS_ALLOWED_ORIGINS=" + origins)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("wildcards are not allowed"));
    }

    private static List<String> origins(InventoryProperties properties) {
        return properties.http().cors().allowedOrigins();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InventoryProperties.class)
    static class PropertiesConfiguration {}
}
