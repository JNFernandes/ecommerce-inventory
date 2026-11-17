package com.ecommerce.inventory.web;

import com.ecommerce.inventory.application.ProductNotFoundException;
import com.ecommerce.inventory.web.dto.ParameterError;
import java.util.List;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error of the catalog into an RFC 9457 problem detail ({@code application/problem+json},
 * US-04 FR-012, research R6). Extending {@link ResponseEntityExceptionHandler} gives Spring MVC's own
 * exceptions (405, 404 for unknown paths, 400 for bad parameters) the same format. Bodies never contain
 * stack traces, SQL or class names. Every {@code 400} lists one {@link ParameterError} per invalid
 * parameter in the {@code errors} property.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Title of every {@code 400}. */
    static final String INVALID_PARAMETERS_TITLE = "Invalid request parameters";

    /** Message for a value that is not a whole number. */
    static final String NOT_A_WHOLE_NUMBER = "must be a whole number";

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

    /** {@code limit} or {@code offset} outside its range: {@code 400} with one error per parameter. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ParameterError> errors = exception.getParameterValidationResults().stream()
                .map(ApiExceptionHandler::toParameterError)
                .toList();
        return handleExceptionInternal(exception, invalidParameters(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    /** A parameter that is not a whole number ({@code limit=abc}, {@code offset=1.5}): {@code 400}. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String parameter = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : String.valueOf(exception.getPropertyName());
        ParameterError error = new ParameterError(parameter, valueText(exception.getValue()), NOT_A_WHOLE_NUMBER);
        return handleExceptionInternal(
                exception, invalidParameters(List.of(error)), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Builds the shared {@code 400} problem detail.
     *
     * @param errors one entry per invalid parameter
     * @return the problem detail
     */
    static ProblemDetail invalidParameters(List<ParameterError> errors) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more request parameters are invalid.");
        problem.setTitle(INVALID_PARAMETERS_TITLE);
        problem.setProperty("errors", List.copyOf(errors));
        return problem;
    }

    private static ParameterError toParameterError(ParameterValidationResult result) {
        String message = result.getResolvableErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .findFirst()
                .orElse("is invalid");
        return new ParameterError(
                String.valueOf(result.getMethodParameter().getParameterName()),
                valueText(result.getArgument()),
                message);
    }

    private static String valueText(@Nullable Object value) {
        return value == null ? "" : value.toString();
    }
}
