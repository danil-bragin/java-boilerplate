package com.acme.bank.accounts.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Request/response payloads for the account API. All money is carried as exact decimal strings. */
final class AccountDtos {

    private AccountDtos() {}

    record MoneyView(@NotBlank String value, @NotBlank String asset) {}

    record OpenAccountRequest(@NotBlank String ownerName, @NotBlank String asset, @Valid MoneyView initialDeposit) {}

    record OpenAccountResponse(String accountId, String iban, String status) {}

    record AccountView(String accountId, String iban, String status) {}

    record StatementLineView(
            String postedAt, String counterpartyAccountId, String signedAmount, String runningBalance) {}

    record StatementView(String accountId, int page, int size, List<StatementLineView> lines) {}

    record StatusView(String accountId, String status) {}

    /** Internal money-truth view: whether a posting exists for a transfer in the ledger. */
    record PostingStatusView(String transferId, boolean posted) {}

    /**
     * Internal synchronous posting request (transfers fast-path). Routes to the SAME
     * {@code PostTransferCommand}/{@code PostTransferHandler} as the async saga, so it inherits the
     * lock + Σ=0 + posting-PK anchor money guarantees and is idempotent by {@code transferId}.
     */
    record PostTransferRequest(
            @NotBlank String transferId,
            @NotBlank String sourceAccountId,
            @NotBlank String destinationAccountId,
            @Valid MoneyView amount) {}

    /** Synchronous posting outcome: {@code status} is POSTED or REJECTED ({@code reason} set when rejected). */
    record PostTransferResultView(String transferId, String status, String reason) {}
}
