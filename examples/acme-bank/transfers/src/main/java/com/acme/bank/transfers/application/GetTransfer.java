package com.acme.bank.transfers.application;

import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Read-side query: load one transfer by id. */
@Service
public class GetTransfer {

    private final Transfers transfers;

    public GetTransfer(Transfers transfers) {
        this.transfers = transfers;
    }

    public Optional<Transfer> handle(TransferId id) {
        return transfers.findById(id);
    }
}
