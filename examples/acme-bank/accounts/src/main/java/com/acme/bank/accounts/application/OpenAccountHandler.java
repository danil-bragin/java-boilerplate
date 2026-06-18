package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.bank.accounts.domain.Account;
import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.AccountNotFoundException;
import com.acme.bank.accounts.domain.Accounts;
import com.acme.bank.accounts.domain.Iban;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.LedgerEntry;
import com.acme.bank.accounts.domain.OpenRequests;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Money;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Opens an account and, if funded, books the opening deposit as a real double-entry posting from the
 * seeded bank-equity account (Σ=0 — no special money-creation path). Idempotent on the client request
 * id via the {@link OpenRequests} anchor.
 */
@Component
public class OpenAccountHandler implements Command.Handler<OpenAccountCommand, OpenAccountResult> {

    /** The seeded system equity account that funds opening deposits (see V5 migration). */
    static final String SYSTEM_EQUITY_ACCOUNT_ID = "bank-equity";

    private final Accounts accounts;
    private final Ledger ledger;
    private final OpenRequests openRequests;

    public OpenAccountHandler(Accounts accounts, Ledger ledger, OpenRequests openRequests) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.openRequests = openRequests;
    }

    @Override
    public OpenAccountResult handle(OpenAccountCommand command) {
        // Idempotency: a retry with the same request id returns the already-opened account.
        var existing = openRequests.findAccountId(command.requestId());
        if (existing.isPresent()) {
            Account account =
                    accounts.findById(existing.get()).orElseThrow(() -> new AccountNotFoundException(existing.get()));
            return new OpenAccountResult(
                    account.id().value(),
                    account.iban().value(),
                    account.status().name());
        }

        AccountId accountId = new AccountId(UUID.randomUUID().toString());
        Iban iban = Iban.forAccount(accountId);
        Account account = new Account(accountId, iban);
        accounts.save(account);
        // Anchor the open on the request id (flush -> PK guard against a concurrent double-open).
        openRequests.record(command.requestId(), accountId);

        Money deposit = command.initialDeposit();
        if (deposit != null && deposit.isPositive()) {
            AccountId equity = new AccountId(SYSTEM_EQUITY_ACCOUNT_ID);
            Posting opening = new Posting(
                    "open-" + accountId.value(),
                    List.of(new LedgerEntry(accountId, deposit), new LedgerEntry(equity, deposit.negate())));
            ledger.save(opening);
        }

        return new OpenAccountResult(
                account.id().value(), account.iban().value(), account.status().name());
    }
}
