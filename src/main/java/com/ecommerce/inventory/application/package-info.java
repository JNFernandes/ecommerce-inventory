/**
 * Application layer: use-case orchestration.
 *
 * <p>Application services load aggregates through repositories, delegate every decision to the
 * domain, persist the result in a single transaction (stock, reservation, processed-event record
 * and any outbound dead-letter record) and hand the resulting domain events to the publisher only
 * after the transaction commits. They MUST NOT contain business rules.
 *
 * <p>The read-only catalog (US-04) is the exception to that flow: {@code ProductQueryService} reads
 * {@code ProductView} / {@code ProductPage} records through the {@code ProductCatalogReader} port in
 * read-only transactions, without locks and without the domain (there is no rule to apply).
 */
package com.ecommerce.inventory.application;
