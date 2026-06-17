package com.acme.cqrs;

/**
 * Marker for commands that must execute inside a database transaction.
 * Strong consistency is the default intent; the {@code TransactionMiddleware} only opens a
 * transaction for commands carrying this marker. Commands without it run non-transactionally.
 */
public interface StronglyConsistent {}
