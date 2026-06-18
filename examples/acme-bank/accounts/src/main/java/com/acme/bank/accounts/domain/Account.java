package com.acme.bank.accounts.domain;

import com.acme.money.Asset;
import java.util.Objects;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

@AggregateRoot
public class Account {

    @Identity
    private final AccountId id;

    private final Iban iban;
    private final Asset asset;
    private AccountStatus status;

    public Account(AccountId id, Iban iban, Asset asset) {
        this(id, iban, asset, AccountStatus.OPEN);
    }

    public Account(AccountId id, Iban iban, Asset asset, AccountStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.iban = Objects.requireNonNull(iban, "iban");
        this.asset = Objects.requireNonNull(asset, "asset");
        this.status = Objects.requireNonNull(status, "status");
    }

    public AccountId id() {
        return id;
    }

    public Iban iban() {
        return iban;
    }

    /** The single currency this account transacts in. Every entry posted to it must match. */
    public Asset asset() {
        return asset;
    }

    public AccountStatus status() {
        return status;
    }

    /** Operational accounts can be debited/credited. */
    public boolean isOperational() {
        return status == AccountStatus.OPEN;
    }

    /** Freeze an OPEN account (OPEN&rarr;FROZEN). Frozen accounts are not operational. */
    public void freeze() {
        if (status != AccountStatus.OPEN) {
            throw new IllegalStateException("cannot freeze an account in status " + status);
        }
        this.status = AccountStatus.FROZEN;
    }

    /** Close an OPEN or FROZEN account (&rarr;CLOSED). Closing a CLOSED account is illegal. */
    public void close() {
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("account is already closed");
        }
        this.status = AccountStatus.CLOSED;
    }
}
