package com.ecommerce.inventory.web;

import com.ecommerce.inventory.application.ProductPage;
import com.ecommerce.inventory.application.ProductQueryService;
import com.ecommerce.inventory.web.dto.ProductPageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only product catalog over HTTP (US-04). Each method calls exactly one {@link ProductQueryService}
 * method and maps the result; nothing else. The parameter constraints are enforced by Spring MVC's
 * built-in method validation, so there is deliberately no class-level {@code @Validated} (research R4).
 */
@RestController
@RequestMapping("/products")
public class ProductController {

    /** Page size when {@code limit} is not given. */
    static final String DEFAULT_LIMIT = "50";

    /** Products skipped when {@code offset} is not given. */
    static final String DEFAULT_OFFSET = "0";

    private final ProductQueryService service;

    /**
     * Creates the controller.
     *
     * @param service catalog queries
     */
    public ProductController(ProductQueryService service) {
        this.service = service;
    }

    /**
     * Lists products in product id order, one page at a time.
     *
     * @param limit maximum number of products, 1 to 200, default 50
     * @param offset number of products to skip, 0 or more, default 0
     * @return the page with the total and the applied limit and offset
     */
    @GetMapping
    public ProductPageResponse list(
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(ProductPage.MAX_LIMIT) int limit,
            @RequestParam(defaultValue = DEFAULT_OFFSET) @Min(0) int offset) {
        return ProductPageResponse.from(service.list(limit, offset));
    }
}
