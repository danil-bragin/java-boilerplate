package com.acme.bank.transfers.domain;

import java.util.List;
import java.util.Optional;

public interface Transfers {
    void save(Transfer transfer);

    Optional<Transfer> findById(TransferId id);

    boolean exists(TransferId id);

    /**
     * Paged query over transfers, optionally filtered by an account (matched as either source or
     * destination) and/or by status. Null filters are ignored. Ordered newest-first by id.
     */
    List<Transfer> query(String accountId, TransferStatus status, int page, int size);
}
