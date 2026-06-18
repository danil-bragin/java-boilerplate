package com.acme.bank.transfers.domain;

public enum TransferStatus {
    REQUESTED,
    SCREENING,
    APPROVED,
    REJECTED,
    POSTING,
    COMPLETED,
    FAILED
}
