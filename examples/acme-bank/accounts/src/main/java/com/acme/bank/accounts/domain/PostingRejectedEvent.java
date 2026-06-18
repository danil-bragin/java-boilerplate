package com.acme.bank.accounts.domain;

import org.jmolecules.event.annotation.DomainEvent;

/** Published when a transfer cannot be posted (e.g. insufficient funds). */
@DomainEvent
public record PostingRejectedEvent(String transferId, String reason) {}
