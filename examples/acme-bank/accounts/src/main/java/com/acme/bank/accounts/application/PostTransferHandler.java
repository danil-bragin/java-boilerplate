package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.bank.accounts.domain.Account;
import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.AccountNotFoundException;
import com.acme.bank.accounts.domain.Accounts;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Money;
import org.springframework.stereotype.Component;

@Component
public class PostTransferHandler implements Command.Handler<PostTransferCommand, PostTransferResult> {

    private final Accounts accounts;
    private final Ledger ledger;

    public PostTransferHandler(Accounts accounts, Ledger ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Override
    public PostTransferResult handle(PostTransferCommand command) {
        if (ledger.existsByTransferId(command.transferId())) {
            return PostTransferResult.posted(command.transferId());
        }

        AccountId sourceId = new AccountId(command.sourceAccountId());
        AccountId destId = new AccountId(command.destinationAccountId());
        Money amount = command.amount();

        // Serialize concurrent debits on the source: take a write lock so the derived-balance
        // read-modify-write below cannot interleave with another posting on the same account.
        // The lock is held to tx end by the surrounding StronglyConsistent transaction.
        Account source = accounts.findByIdForUpdate(sourceId).orElseThrow(() -> new AccountNotFoundException(sourceId));
        // Destination is only credited (no overdraft possible) — a plain read, no lock. One lock per
        // tx (the source) means no lock-ordering / deadlock concern.
        Account dest = accounts.findById(destId).orElseThrow(() -> new AccountNotFoundException(destId));

        // Idempotency re-check UNDER the source lock: by serializing on the source, any earlier posting
        // for this transferId has now committed and is visible, so a redelivered duplicate short-circuits
        // here. (The top-of-method check is only a cheap fast path read before the lock is taken.)
        if (ledger.existsByTransferId(command.transferId())) {
            return PostTransferResult.posted(command.transferId());
        }

        if (!source.isOperational() || !dest.isOperational()) {
            return PostTransferResult.rejected(command.transferId(), "ACCOUNT_NOT_OPERATIONAL");
        }

        // Per-account single-asset invariant: both legs must transact in their account's own currency.
        if (!source.asset().equals(amount.asset()) || !dest.asset().equals(amount.asset())) {
            return PostTransferResult.rejected(command.transferId(), "ACCOUNT_ASSET_MISMATCH");
        }

        Money balance = ledger.balance(sourceId, amount.asset());
        if (balance.compareTo(amount) < 0) {
            return PostTransferResult.rejected(command.transferId(), "INSUFFICIENT_FUNDS");
        }

        ledger.save(Posting.transfer(command.transferId(), sourceId, destId, amount));
        return PostTransferResult.posted(command.transferId());
    }
}
