package com.ecommerce.inventory.application;

/** Turns a failure into the text stored as {@code last_error}. */
final class Failures {

    private Failures() {}

    /**
     * Describes an exception and its causes on one line.
     *
     * @param failure the failure
     * @return the exception and each cause, separated by {@code | caused by}
     */
    static String describe(Exception failure) {
        StringBuilder description = new StringBuilder(failure.toString());
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            description.append(" | caused by ").append(cause);
        }
        return description.toString();
    }
}
