package com.ecommerce.inventory.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.inventory.application.ProductNotFoundException;
import com.ecommerce.inventory.application.ProductPage;
import com.ecommerce.inventory.application.ProductQueryService;
import com.ecommerce.inventory.application.ProductView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The catalog HTTP contract (specs/004-products-api/contracts/products-api.md) through MockMvc. */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final ProductView MOUSE = new ProductView("SKU-001", "Wireless Mouse", 10);
    private static final ProductView WEBCAM = new ProductView("SKU-005", "Webcam HD", 0);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductQueryService service;

    @Test
    @DisplayName("should return the page with items, total, limit and offset")
    void shouldReturnPage() throws Exception {
        when(service.list(2, 3)).thenReturn(new ProductPage(List.of(MOUSE, WEBCAM), 5, 2, 3));

        mvc.perform(get("/products").param("limit", "2").param("offset", "3"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items[0].keys()", containsInAnyOrder("id", "name", "availableQuantity")))
                .andExpect(jsonPath("$.items[0].id").value("SKU-001"))
                .andExpect(jsonPath("$.items[0].name").value("Wireless Mouse"))
                .andExpect(jsonPath("$.items[0].availableQuantity").value(10))
                .andExpect(jsonPath("$.items[1].id").value("SKU-005"))
                .andExpect(jsonPath("$.items[1].availableQuantity").value(0))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(3));
    }

    @Test
    @DisplayName("should expose exactly the documented top-level fields")
    void shouldExposeDocumentedFields() throws Exception {
        when(service.list(50, 0)).thenReturn(new ProductPage(List.of(), 0, 50, 0));

        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("items", "total", "limit", "offset")));
    }

    @Test
    @DisplayName("should apply limit 50 and offset 0 by default")
    void shouldApplyDefaults() throws Exception {
        when(service.list(50, 0)).thenReturn(new ProductPage(List.of(MOUSE), 1, 50, 0));

        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0));

        verify(service).list(50, 0);
    }

    @Test
    @DisplayName("should pass the requested limit and offset to the query service")
    void shouldPassLimitAndOffset() throws Exception {
        when(service.list(200, 7)).thenReturn(new ProductPage(List.of(), 5, 200, 7));

        mvc.perform(get("/products").param("limit", "200").param("offset", "7")).andExpect(status().isOk());

        verify(service).list(200, 7);
    }

    @Test
    @DisplayName("should return one product")
    void shouldReturnOneProduct() throws Exception {
        when(service.get("SKU-001")).thenReturn(MOUSE);

        mvc.perform(get("/products/SKU-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("id", "name", "availableQuantity")))
                .andExpect(jsonPath("$.id").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andExpect(jsonPath("$.availableQuantity").value(10));
    }

    @Test
    @DisplayName("should answer 404 with a problem body naming the missing product")
    void shouldAnswerNotFoundProblem() throws Exception {
        when(service.get("SKU-999")).thenThrow(new ProductNotFoundException("SKU-999"));

        mvc.perform(get("/products/SKU-999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Product not found"))
                .andExpect(jsonPath("$.detail").value("Product SKU-999 not found."))
                .andExpect(jsonPath("$.instance").value("/products/SKU-999"))
                .andExpect(jsonPath("$.productId").value("SKU-999"));
    }

    @Test
    @DisplayName("should look up the id exactly as given")
    void shouldPassIdUnchanged() throws Exception {
        when(service.get("sku-003")).thenThrow(new ProductNotFoundException("sku-003"));

        mvc.perform(get("/products/sku-003")).andExpect(status().isNotFound());

        verify(service).get("sku-003");
    }
}
