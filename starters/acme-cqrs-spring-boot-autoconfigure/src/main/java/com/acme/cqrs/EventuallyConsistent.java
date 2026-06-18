package com.acme.cqrs;

/**
 * Marker for commands/flows whose effects converge <em>asynchronously</em> — there is no surrounding
 * database transaction guarantee; correctness relies on the outbox/inbox pattern plus reconciliation
 * (e.g. the transfers saga and its money-safe stuck-saga reconciler).
 *
 * <p>Sibling of {@link StronglyConsistent}. The {@code TransactionMiddleware} treats an
 * {@code EventuallyConsistent} command exactly like an unmarked one — it opens <strong>no</strong>
 * transaction (only {@link StronglyConsistent} does). The marker is documentation of intent and a
 * seam for future routing (e.g. metrics, dedicated executors); it deliberately does not change the
 * transactional behaviour today.
 */
public interface EventuallyConsistent {}
