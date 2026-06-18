package com.acme.bank.transfers.domain;

import java.util.Optional;

public interface Transfers {
    void save(Transfer transfer);

    Optional<Transfer> findById(TransferId id);

    boolean exists(TransferId id);
}
