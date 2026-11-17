package com.ecommerce.inventory.web;

import com.ecommerce.inventory.application.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error of the catalog into an RFC 9457 problem detail ({@code application/problem+json},
 * US-04 FR-012, research R6). Extending {@link ResponseEntityExceptionHandler} gives Spring MVC's own
 * exceptions (405, 404 for unknown paths, 400 for bad parameters) the same format. Bodies never contain
 * stack traces, SQL or class names.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * An unknown product id: {@code 404} naming the id in {@code detail} and in {@code productId}.
     *
     * @param exception the query outcome
     * @return the problem detail
     */
    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail productNotFound(ProductNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "Product " + exception.productId() + " not found.");
        problem.setTitle("Product not found");
        problem.setProperty("productId", exception.productId());
        return problem;
    }
}
