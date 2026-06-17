package com.acme.bank.accounts.domain;

import com.acme.money.Money;
import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

/** One side of a posting: a signed amount applied to an account (debit negative, credit positive). */
@ValueObject
public record LedgerEntry(AccountId accountId, Money amount) {
    public LedgerEntry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
    }
}
