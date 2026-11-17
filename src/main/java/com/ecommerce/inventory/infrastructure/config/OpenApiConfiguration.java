package com.ecommerce.inventory.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the read-only catalog in the OpenAPI document served at {@code /v3/api-docs} and shown by
 * the Swagger UI at {@code /swagger-ui.html} (US-04 FR-022, research R9). springdoc builds the document
 * on the first request; it needs neither Kafka nor the database at startup.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    /**
     * Title, version and description of the catalog API.
     *
     * @return the OpenAPI model springdoc completes from the controller
     */
    @Bean
    public OpenAPI catalogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ecommerce-inventory catalog")
                        .version("v1")
                        .description("Public, read-only product catalog with available stock. No authentication."
                                + " availableQuantity is a snapshot of saved stock, read without locks: the"
                                + " authoritative availability check happens when the order is reserved, so an"
                                + " order for a product shown as available can still be rejected. Errors are"
                                + " RFC 9457 problem details. No endpoint creates, changes or deletes data."));
    }
}
