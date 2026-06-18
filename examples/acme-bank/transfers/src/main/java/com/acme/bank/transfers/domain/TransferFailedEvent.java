package com.acme.bank.transfers.domain;

import org.jmolecules.event.annotation.DomainEvent;

/** Published when a transfer is rejected at screening or failed at posting. */
@DomainEvent
public record TransferFailedEvent(String transferId, String reason) {}
