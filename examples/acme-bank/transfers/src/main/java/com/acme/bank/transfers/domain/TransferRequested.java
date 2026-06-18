package com.acme.bank.transfers.domain;

import com.acme.money.Money;
import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record TransferRequested(
        String transferId, String sourceAccountId, String destinationAccountId, Money amount, String requestedBy) {}
