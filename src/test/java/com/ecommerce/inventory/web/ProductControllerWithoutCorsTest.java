package com.ecommerce.inventory.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.inventory.application.ProductPage;
import com.ecommerce.inventory.application.ProductQueryService;
import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no origin configured (the default), no CORS header is ever sent (US-04 FR-023). The property
 * is set to empty explicitly, so a {@code CORS_ALLOWED_ORIGINS} variable in the shell cannot change
 * the result.
 */
@WebMvcTest(ProductController.class)
@EnableConfigurationProperties(InventoryProperties.class)
@TestPropertySource(properties = "inventory.http.cors.allowed-origins=")
class ProductControllerWithoutCorsTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductQueryService service;

    @Test
    @DisplayName("should send no CORS headers when no origin is configured")
    void shouldSendNoCorsHeaders() throws Exception {
        when(service.list(50, 0)).thenReturn(new ProductPage(List.of(), 0, 50, 0));

        mvc.perform(get("/products").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("should not allow a preflight when no origin is configured")
    void shouldNotAllowPreflight() throws Exception {
        mvc.perform(options("/products")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
