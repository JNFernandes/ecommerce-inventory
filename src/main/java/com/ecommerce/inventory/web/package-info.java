/**
 * Web layer: the inbound HTTP adapter for the read-only product catalog (US-04, constitution
 * Principle II, ADR 0007).
 *
 * <p>Controllers handle I/O only: they read request parameters, call exactly one method of the
 * application query service and map the result to response records from {@code web.dto}. They contain
 * no logic and never create, change or delete data. Domain objects and JPA entities never reach a
 * response.
 */
package com.ecommerce.inventory.web;
