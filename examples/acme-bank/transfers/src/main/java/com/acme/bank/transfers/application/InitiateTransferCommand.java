package com.acme.bank.transfers.application;

import an.awesome.pipelinr.Command;
import com.acme.cqrs.StronglyConsistent;
import com.acme.money.Money;

public record InitiateTransferCommand(
        String transferId, String sourceAccountId, String destinationAccountId, Money amount, String requestedBy)
        implements Command<String>, StronglyConsistent {}
