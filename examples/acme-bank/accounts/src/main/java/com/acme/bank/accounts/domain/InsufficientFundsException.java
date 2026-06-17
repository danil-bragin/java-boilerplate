package com.acme.bank.accounts.domain;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(AccountId id) {
        super("insufficient funds in account: " + id.value());
    }
}
