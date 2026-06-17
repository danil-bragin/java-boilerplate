package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
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

        accounts.findById(sourceId).orElseThrow(() -> new AccountNotFoundException(sourceId));
        accounts.findById(destId).orElseThrow(() -> new AccountNotFoundException(destId));

        Money balance = ledger.balance(sourceId, amount.asset());
        if (balance.compareTo(amount) < 0) {
            return PostTransferResult.rejected(command.transferId(), "INSUFFICIENT_FUNDS");
        }

        ledger.save(Posting.transfer(command.transferId(), sourceId, destId, amount));
        return PostTransferResult.posted(command.transferId());
    }
}
