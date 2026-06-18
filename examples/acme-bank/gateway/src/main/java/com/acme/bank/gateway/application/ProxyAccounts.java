package com.acme.bank.gateway.application;

import com.acme.bank.gateway.api.dto.AccountView;
import com.acme.bank.gateway.api.dto.Money;
import com.acme.bank.gateway.api.dto.OpenAccountRequest;
import com.acme.bank.gateway.api.dto.StatementPage;
import com.acme.bank.gateway.client.AccountsRestClient;
import org.springframework.stereotype.Service;

/** Edge composition over the downstream accounts service: forwards each operation to the client. */
@Service
public class ProxyAccounts {

    private final AccountsRestClient accounts;

    public ProxyAccounts(AccountsRestClient accounts) {
        this.accounts = accounts;
    }

    public AccountView open(OpenAccountRequest request, String idempotencyKey) {
        return accounts.open(request, idempotencyKey);
    }

    public AccountView get(String id) {
        return accounts.get(id);
    }

    public Money balance(String id) {
        return accounts.balance(id);
    }

    public StatementPage statement(String id, Integer page, Integer size) {
        return accounts.statement(id, page, size);
    }
}
