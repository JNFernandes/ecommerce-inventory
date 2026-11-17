package com.ecommerce.inventory.infrastructure.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin reads of the catalog (US-04 FR-023, research R8). Only the origins listed in
 * {@code CORS_ALLOWED_ORIGINS} may call {@code /products} from a browser, and only to read
 * ({@code GET}, {@code HEAD}; preflight {@code OPTIONS} is answered by Spring). With no origin
 * configured no mapping is registered, so no CORS header is ever sent and browsers block cross-origin
 * calls. The documentation endpoints get no CORS mapping.
 */
@Configuration(proxyBeanMethods = false)
public class WebCorsConfiguration implements WebMvcConfigurer {

    /** Seconds a browser may cache a preflight answer. */
    static final long PREFLIGHT_MAX_AGE_SECONDS = 1800;

    private final List<String> allowedOrigins;

    /**
     * Creates the configuration.
     *
     * @param properties service configuration with the allowed origins
     */
    public WebCorsConfiguration(InventoryProperties properties) {
        this.allowedOrigins = properties.http().cors().allowedOrigins();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        String[] origins = allowedOrigins.toArray(String[]::new);
        for (String path : List.of("/products", "/products/**")) {
            registry.addMapping(path)
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "HEAD")
                    .allowCredentials(false)
                    .maxAge(PREFLIGHT_MAX_AGE_SECONDS);
        }
    }
}
