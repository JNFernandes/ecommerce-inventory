package com.ecommerce.inventory.web;

import com.ecommerce.inventory.web.dto.ParameterError;
import java.util.List;

/** Paging parameters that are empty or repeated (US-04 FR-010); answered with {@code 400}. */
public class InvalidQueryParameterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One entry per offending parameter. */
    private final transient List<ParameterError> errors;

    /**
     * Creates the exception.
     *
     * @param errors one entry per offending parameter, not empty
     */
    public InvalidQueryParameterException(List<ParameterError> errors) {
        super("Invalid query parameters: " + errors);
        this.errors = List.copyOf(errors);
    }

    /**
     * The offending parameters.
     *
     * @return one entry per parameter
     */
    public List<ParameterError> errors() {
        return errors;
    }
}
