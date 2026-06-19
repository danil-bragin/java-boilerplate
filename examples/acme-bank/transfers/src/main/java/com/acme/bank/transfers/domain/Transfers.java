package com.acme.bank.transfers.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface Transfers {
    void save(Transfer transfer);

    Optional<Transfer> findById(TransferId id);

    boolean exists(TransferId id);

    /**
     * Count of a source account's transfers created since {@code since}. Drives the per-source fast-path
     * velocity guard (BANK-22 Fix 1): a source above the cap is routed to the async screened slow-path.
     */
    long countBySourceSince(String sourceAccountId, Instant since);

    /**
     * Transfers stuck in one of the given non-terminal {@code statuses} whose last update is older
     * than {@code cutoff}, oldest-first, capped at {@code limit}. Drives the saga reconciler sweep.
     */
    List<Transfer> findStuck(List<TransferStatus> statuses, Instant cutoff, int limit);

    /**
     * Paged query over transfers, optionally filtered by an account (matched as either source or
     * destination) and/or by status. Null filters are ignored. Ordered newest-first by id.
     */
    List<Transfer> query(String accountId, TransferStatus status, int page, int size);
}
