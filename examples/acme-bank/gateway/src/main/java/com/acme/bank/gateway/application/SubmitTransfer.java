package com.acme.bank.gateway.application;

import com.acme.bank.gateway.api.dto.CreateTransferRequest;
import com.acme.bank.gateway.client.TransfersRestClient;
import org.springframework.stereotype.Service;

/** Forwards a create-transfer request to the downstream transfers service. */
@Service
public class SubmitTransfer {

    private final TransfersRestClient transfers;

    public SubmitTransfer(TransfersRestClient transfers) {
        this.transfers = transfers;
    }

    public String submit(CreateTransferRequest request, String idempotencyKey) {
        return transfers.create(request, idempotencyKey);
    }
}
