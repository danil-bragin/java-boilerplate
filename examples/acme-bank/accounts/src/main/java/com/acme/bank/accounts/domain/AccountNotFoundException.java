package com.acme.bank.accounts.domain;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(AccountId id) {
        super("account not found: " + id.value());
    }
}
