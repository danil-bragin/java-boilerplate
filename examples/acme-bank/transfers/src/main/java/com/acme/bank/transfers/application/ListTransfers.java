package com.acme.bank.transfers.application;

import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferStatus;
import com.acme.bank.transfers.domain.Transfers;
import java.util.List;
import org.springframework.stereotype.Service;

/** Read-side query: list transfers filtered by account and/or status, paged. */
@Service
public class ListTransfers {

    private final Transfers transfers;

    public ListTransfers(Transfers transfers) {
        this.transfers = transfers;
    }

    public List<Transfer> handle(String accountId, TransferStatus status, int page, int size) {
        return transfers.query(accountId, status, page, size);
    }
}
