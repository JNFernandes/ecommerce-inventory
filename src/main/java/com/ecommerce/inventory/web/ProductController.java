package com.ecommerce.inventory.web;

import com.ecommerce.inventory.application.ProductPage;
import com.ecommerce.inventory.application.ProductQueryService;
import com.ecommerce.inventory.web.dto.ProductPageResponse;
import com.ecommerce.inventory.web.dto.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@Tag(
        name = "Products",
        description = "Public, read-only catalog with available stock. Quantities are snapshots; the order"
                + " reservation is the authoritative check.")
@ApiResponse(
        responseCode = "405",
        description = "Write methods (POST, PUT, PATCH, DELETE) are not supported; nothing changes.",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "500",
        description = "Unexpected failure, for example the database is unreachable. Generic detail only.",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
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
    @Operation(
            summary = "List products with their available quantity",
            description = "Products in product id order (byte order: SKU-002 before SKU-010 before sku-001),"
                    + " including products with 0 available. items and total come from one snapshot.")
    @ApiResponse(responseCode = "200", description = "One page of the catalog.")
    @ApiResponse(
            responseCode = "400",
            description = "limit or offset is out of range, not a whole number, empty or repeated; errors lists"
                    + " each invalid parameter.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ProductPageResponse list(
            @Parameter(
                            description = "Maximum number of products to return.",
                            schema = @Schema(type = "integer", minimum = "1", maximum = "200", defaultValue = "50"))
                    @RequestParam(defaultValue = DEFAULT_LIMIT)
                    @Min(1)
                    @Max(ProductPage.MAX_LIMIT)
                    int limit,
            @Parameter(
                            description = "Number of products to skip before the first returned product.",
                            schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
                    @RequestParam(defaultValue = DEFAULT_OFFSET)
                    @Min(0)
                    int offset) {
        return ProductPageResponse.from(service.list(limit, offset));
    }

    /**
     * Returns one product by its exact id (case-sensitive). An unknown id becomes a {@code 404} problem
     * detail in {@link ApiExceptionHandler}.
     *
     * @param id product id, URL-decoded
     * @return the product
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get one product with its available quantity",
            description = "The id is matched exactly, case-sensitive, after URL decoding.")
    @ApiResponse(responseCode = "200", description = "The product.")
    @ApiResponse(
            responseCode = "404",
            description = "No product has this id; productId repeats the requested id.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ProductResponse get(
            @Parameter(description = "Product id as used in orders, for example SKU-001.", example = "SKU-001")
                    @PathVariable
                    String id) {
        return ProductResponse.from(service.get(id));
    }
}
