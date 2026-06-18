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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    /**
     * The open is attempted in its OWN inner transaction so a concurrent double-open's PK violation
     * rolls back only that attempt, leaving the surrounding transaction clean enough to re-resolve and
     * return the winning account (instead of poisoning it and surfacing a 500).
     */
    private final TransactionTemplate openTx;

    public OpenAccountHandler(
            Accounts accounts, Ledger ledger, OpenRequests openRequests, PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.openRequests = openRequests;
        this.openTx = new TransactionTemplate(txManager);
        this.openTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public OpenAccountResult handle(OpenAccountCommand command) {
        // Idempotency: a retry with the same request id returns the already-opened account.
        var existing = openRequests.findAccountId(command.requestId());
        if (existing.isPresent()) {
            return resolve(existing.get());
        }

        AccountId accountId = new AccountId(UUID.randomUUID().toString());
        try {
            // Attempt the open in its own transaction: the open_request flush is the PK guard against a
            // concurrent double-open, and on violation only this inner transaction rolls back.
            return openTx.execute(status -> {
                Iban iban = Iban.forAccount(accountId);
                Account account = new Account(accountId, iban, command.asset());
                accounts.save(account);
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
                        account.id().value(),
                        account.iban().value(),
                        account.status().name());
            });
        } catch (DataIntegrityViolationException raceLost) {
            // A true-concurrent open with the same request id won the race: re-resolve and return the
            // winner's account (no double-open) rather than surfacing the constraint violation as a 500.
            AccountId winner = openRequests.findAccountId(command.requestId()).orElseThrow(() -> raceLost);
            return resolve(winner);
        }
    }

    private OpenAccountResult resolve(AccountId accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        return new OpenAccountResult(
                account.id().value(), account.iban().value(), account.status().name());
    }
}
