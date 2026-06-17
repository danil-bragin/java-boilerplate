package com.acme.bank.accounts.domain;

import java.util.Optional;

/** Out-port: load accounts. */
public interface Accounts {
    Optional<Account> findById(AccountId id);
}
