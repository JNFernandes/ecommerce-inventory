package com.ecommerce.inventory.web.dto;

/**
 * One invalid request parameter, listed in the {@code errors} property of a {@code 400} problem detail.
 *
 * @param parameter name of the query parameter, for example {@code limit}
 * @param rejectedValue the value as sent, or an empty string when it was empty
 * @param message the rule it broke, for example {@code must be less than or equal to 200}
 */
public record ParameterError(String parameter, String rejectedValue, String message) {}
