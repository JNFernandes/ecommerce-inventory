package com.ecommerce.inventory.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One invalid request parameter, listed in the {@code errors} property of a {@code 400} problem detail.
 *
 * @param parameter name of the query parameter, for example {@code limit}
 * @param rejectedValue the value as sent, or an empty string when it was empty
 * @param message the rule it broke, for example {@code must be less than or equal to 200}
 */
@Schema(description = "One invalid request parameter, in the errors property of a 400 problem detail.")
public record ParameterError(
        @Schema(description = "Query parameter name.", example = "limit")
        String parameter,

        @Schema(description = "Value as sent; empty when the parameter was empty.", example = "201")
        String rejectedValue,

        @Schema(description = "The rule it broke.", example = "must be less than or equal to 200")
        String message) {}
