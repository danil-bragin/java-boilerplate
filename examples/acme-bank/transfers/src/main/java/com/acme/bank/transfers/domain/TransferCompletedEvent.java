package com.acme.bank.transfers.domain;

import org.jmolecules.event.annotation.DomainEvent;

/** Published when the ledger confirms the posting and the transfer is completed. */
@DomainEvent
public record TransferCompletedEvent(String transferId) {}
