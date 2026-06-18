package com.acme.bank.accounts.domain;

import java.util.Optional;

/** Out-port: load and persist accounts. */
public interface Accounts {
    Optional<Account> findById(AccountId id);

    /** Insert or update an account (new accounts and lifecycle transitions). */
    void save(Account account);
}
