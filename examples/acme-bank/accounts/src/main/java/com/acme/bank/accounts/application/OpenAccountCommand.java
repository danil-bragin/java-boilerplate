package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.cqrs.StronglyConsistent;
import com.acme.money.Asset;
import com.acme.money.Money;

/**
 * Open a new account, optionally funding it with an opening deposit. Strongly consistent: the account
 * row, the idempotency anchor, and the (balanced) opening posting all commit atomically.
 *
 * @param requestId client-supplied idempotency key — a retry with the same id never double-opens
 * @param ownerName the account owner (display only in this example)
 * @param asset the account's currency
 * @param initialDeposit opening deposit, or {@code null} for none
 */
public record OpenAccountCommand(String requestId, String ownerName, Asset asset, Money initialDeposit)
        implements Command<OpenAccountResult>, StronglyConsistent {}
