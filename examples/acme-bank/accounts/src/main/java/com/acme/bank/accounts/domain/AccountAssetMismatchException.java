package com.acme.bank.accounts.domain;

import com.acme.money.Asset;

/**
 * Thrown when an entry's asset differs from the destination/source account's established asset. The
 * per-account single-asset invariant: an account only ever holds one currency, so a mismatched entry
 * is rejected rather than silently summed into a different-asset balance.
 */
public class AccountAssetMismatchException extends RuntimeException {

    private final AccountId accountId;
    private final Asset accountAsset;
    private final Asset entryAsset;

    public AccountAssetMismatchException(AccountId accountId, Asset accountAsset, Asset entryAsset) {
        super("account " + accountId.value() + " holds " + accountAsset.code() + " but entry is " + entryAsset.code());
        this.accountId = accountId;
        this.accountAsset = accountAsset;
        this.entryAsset = entryAsset;
    }

    public AccountId accountId() {
        return accountId;
    }

    public Asset accountAsset() {
        return accountAsset;
    }

    public Asset entryAsset() {
        return entryAsset;
    }
}
