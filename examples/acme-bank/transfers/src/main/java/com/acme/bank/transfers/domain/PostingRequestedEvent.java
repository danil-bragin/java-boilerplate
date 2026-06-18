package com.acme.bank.transfers.domain;

import com.acme.money.Money;
import org.jmolecules.event.annotation.DomainEvent;

/** Published when a transfer is approved and the ledger posting is requested. */
@DomainEvent
public record PostingRequestedEvent(
        String transferId, String sourceAccountId, String destinationAccountId, Money amount) {}
