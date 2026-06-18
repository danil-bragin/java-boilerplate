package com.acme.bank.accounts.domain;

import com.acme.money.Asset;
import com.acme.money.Money;
import java.time.Instant;
import java.util.List;

/** Out-port: persist postings and derive balances/statements from entries (no materialized balance). */
public interface Ledger {

    /** Persist a balanced posting atomically. */
    void save(Posting posting);

    /** True if a posting for this transfer already exists (idempotency check). */
    boolean existsByTransferId(String transferId);

    /** Derived balance = SUM of the account's entries for the asset (no materialized balance). */
    Money balance(AccountId accountId, Asset asset);

    /**
     * Derived balance for an account without naming the asset up front: resolves the account's asset
     * from the ACCOUNT itself (one asset per account in this model) and sums its entries. A no-deposit
     * account therefore reports zero in its own currency, never a ledger-derived guess.
     */
    Money balanceOf(AccountId accountId);

    /**
     * Page of this account's ledger entries, time-bounded [from, to) and ordered oldest-first, with
     * the per-entry counterparty resolved. Used to fold a running-balance statement in the query
     * service.
     */
    List<PostedEntry> entriesFor(AccountId accountId, Instant from, Instant to, int page, int size);

    /** SUM of this account's entries strictly before {@code at} — the opening balance for a page. */
    Money balanceBefore(AccountId accountId, Instant at);

    /** A persisted ledger entry enriched with its posting time and counterparty. */
    record PostedEntry(Instant postedAt, String transferId, String counterpartyAccountId, Money amount) {}
}
