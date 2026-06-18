package com.acme.bank.accounts.domain;

import com.acme.money.Money;
import java.time.Instant;
import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

/**
 * One derived line of an account statement. The {@code runningBalance} is computed by folding the
 * account's entries in chronological order (it is never stored — see the standing no-materialized-
 * balance constraint).
 */
@ValueObject
public record StatementLine(Instant postedAt, String counterpartyAccountId, Money signedAmount, Money runningBalance) {
    public StatementLine {
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(signedAmount, "signedAmount");
        Objects.requireNonNull(runningBalance, "runningBalance");
    }
}
