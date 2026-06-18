package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.cqrs.StronglyConsistent;

/** Apply a lifecycle transition (FREEZE or CLOSE) to an account, atomically. */
public record ChangeAccountStatusCommand(String accountId, Transition transition)
        implements Command<ChangeAccountStatusResult>, StronglyConsistent {

    public enum Transition {
        FREEZE,
        CLOSE
    }
}
