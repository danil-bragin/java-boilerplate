package com.acme.bank.accounts.domain;

import com.acme.money.Asset;
import com.acme.money.Money;

/** Out-port: persist postings and derive balances from entries. */
public interface Ledger {

    /** Persist a balanced posting atomically. */
    void save(Posting posting);

    /** True if a posting for this transfer already exists (idempotency check). */
    boolean existsByTransferId(String transferId);

    /** Derived balance = SUM of the account's entries for the asset (no materialized balance). */
    Money balance(AccountId accountId, Asset asset);
}
