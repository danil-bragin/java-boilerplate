package com.acme.bank.accounts.application;

public record OpenAccountResult(String accountId, String iban, String status) {}
