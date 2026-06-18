package com.acme.bank.transfers.domain;

public enum TransferStatus {
    REQUESTED,
    APPROVED,
    POSTING,
    COMPLETED,
    FAILED
}
