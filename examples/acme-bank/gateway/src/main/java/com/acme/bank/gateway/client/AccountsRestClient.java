package com.acme.bank.gateway.client;

import com.acme.bank.gateway.api.dto.AccountView;
import com.acme.bank.gateway.api.dto.Money;
import com.acme.bank.gateway.api.dto.StatementPage;

/**
 * Out-adapter port to the downstream {@code accounts} service. Implementations forward the request
 * (with the caller's {@code Idempotency-Key} where relevant) and return the proxied response mapped
 * onto the generated DTOs.
 */
public interface AccountsRestClient {

    AccountView open(com.acme.bank.gateway.api.dto.OpenAccountRequest request, String idempotencyKey);

    AccountView get(String id);

    Money balance(String id);

    StatementPage statement(String id, Integer page, Integer size);
}
