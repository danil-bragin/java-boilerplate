package com.acme.bank.accounts.domain;

import org.jmolecules.event.annotation.DomainEvent;

/** Published when a transfer has been successfully posted to the ledger. */
@DomainEvent
public record LedgerPostedEvent(String transferId) {}
