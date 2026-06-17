package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.cqrs.StronglyConsistent;
import com.acme.money.Money;

/** Post a transfer into the ledger. Strongly consistent: the posting is atomic. */
public record PostTransferCommand(String transferId, String sourceAccountId, String destinationAccountId, Money amount)
        implements Command<PostTransferResult>, StronglyConsistent {}
