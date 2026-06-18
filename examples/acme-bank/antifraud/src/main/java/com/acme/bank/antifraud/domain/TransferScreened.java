package com.acme.bank.antifraud.domain;

import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record TransferScreened(String transferId, boolean approved, String reason) {}
