package com.acme.bank.antifraud.application;

/** Out-port for persisting and querying screening decisions. */
public interface ScreeningStore {

    void save(String transferId, String sourceAccountId, boolean approved, String reason);

    boolean existsByTransferId(String transferId);

    int velocity(String sourceAccountId);
}
