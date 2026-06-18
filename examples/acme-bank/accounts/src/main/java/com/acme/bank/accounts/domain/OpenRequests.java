package com.acme.bank.accounts.domain;

import java.util.Optional;

/** Out-port: idempotency anchor for account opening, keyed by client request id. */
public interface OpenRequests {

    /** The account already opened for this request id, if any. */
    Optional<AccountId> findAccountId(String requestId);

    /**
     * Record that {@code requestId} opened {@code accountId}. Flushes so a concurrent or retried open
     * with the same request id fails the primary-key constraint and rolls back.
     */
    void record(String requestId, AccountId accountId);
}
