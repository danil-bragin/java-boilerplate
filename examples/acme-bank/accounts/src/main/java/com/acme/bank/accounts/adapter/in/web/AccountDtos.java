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
}
