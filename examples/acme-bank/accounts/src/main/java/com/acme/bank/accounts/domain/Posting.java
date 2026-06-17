package com.acme.bank.accounts.domain;

import com.acme.money.Money;
import java.util.List;
import java.util.Objects;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

/**
 * An immutable, balanced double-entry transaction. The core invariant: the signed amounts of all
 * entries sum to zero (within one asset). Append-only — corrections are new postings, never updates.
 */
@AggregateRoot
public class Posting {

    @Identity
    private final String transferId;

    private final List<LedgerEntry> entries;

    public Posting(String transferId, List<LedgerEntry> entries) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() < 2) {
            throw new IllegalArgumentException("a posting needs at least two entries");
        }
        Money sum = entries.get(0).amount();
        for (int i = 1; i < entries.size(); i++) {
            sum = sum.add(entries.get(i).amount());
        }
        if (!sum.isZero()) {
            throw new IllegalArgumentException("posting entries must balance to zero, got " + sum);
        }
        this.entries = List.copyOf(entries);
    }

    /** Build a two-entry transfer posting: debit source, credit destination. */
    public static Posting transfer(String transferId, AccountId source, AccountId destination, Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }
        return new Posting(
                transferId, List.of(new LedgerEntry(source, amount.negate()), new LedgerEntry(destination, amount)));
    }

    public String transferId() {
        return transferId;
    }

    public List<LedgerEntry> entries() {
        return entries;
    }
}
