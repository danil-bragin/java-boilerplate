package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.bank.accounts.domain.Account;
import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.AccountNotFoundException;
import com.acme.bank.accounts.domain.Accounts;
import org.springframework.stereotype.Component;

/** Loads an account, applies the lifecycle transition (the aggregate guards legality), and saves it. */
@Component
public class ChangeAccountStatusHandler
        implements Command.Handler<ChangeAccountStatusCommand, ChangeAccountStatusResult> {

    private final Accounts accounts;

    public ChangeAccountStatusHandler(Accounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public ChangeAccountStatusResult handle(ChangeAccountStatusCommand command) {
        AccountId id = new AccountId(command.accountId());
        Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        switch (command.transition()) {
            case FREEZE -> account.freeze();
            case CLOSE -> account.close();
        }
        accounts.save(account);
        return new ChangeAccountStatusResult(
                account.id().value(), account.status().name());
    }
}
