package com.acme.bank.transfers.adapter.in.web;

import com.acme.bank.transfers.domain.Transfer;

/** Read DTO for a transfer. Amount is an exact decimal string at the asset's scale (never a float). */
public record TransferView(
        String transferId,
        String status,
        String amount,
        String asset,
        String sourceAccountId,
        String destinationAccountId,
        String failureReason) {

    static TransferView of(Transfer t) {
        String formatted = t.amount().format(); // "100.00 USD"
        int space = formatted.lastIndexOf(' ');
        return new TransferView(
                t.id().value(),
                t.status().name(),
                formatted.substring(0, space),
                t.amount().asset().code(),
                t.sourceAccountId(),
                t.destinationAccountId(),
                t.failureReason());
    }
}
