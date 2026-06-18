package com.acme.bank.gateway.web;

import com.acme.bank.gateway.api.AccountsApi;
import com.acme.bank.gateway.api.dto.AccountView;
import com.acme.bank.gateway.api.dto.Money;
import com.acme.bank.gateway.api.dto.OpenAccountRequest;
import com.acme.bank.gateway.api.dto.StatementPage;
import com.acme.bank.gateway.application.ProxyAccounts;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Edge controller implementing the generated {@link AccountsApi}. Proxies each account operation to
 * the downstream accounts service via {@link ProxyAccounts} (resilience4j-guarded {@code RestClient}).
 * Implementing the generated interface keeps the spec the source of truth (a missing operation is a
 * compile error).
 */
@RestController
public class AccountController implements AccountsApi {

    private final ProxyAccounts proxy;

    public AccountController(ProxyAccounts proxy) {
        this.proxy = proxy;
    }

    @Override
    public ResponseEntity<AccountView> openAccount(String idempotencyKey, OpenAccountRequest openAccountRequest) {
        AccountView view = proxy.open(openAccountRequest, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @Override
    public ResponseEntity<AccountView> getAccount(String id) {
        return ResponseEntity.ok(proxy.get(id));
    }

    @Override
    public ResponseEntity<Money> getAccountBalance(String id) {
        return ResponseEntity.ok(proxy.balance(id));
    }

    @Override
    public ResponseEntity<StatementPage> getAccountStatement(String id, Integer page, Integer size) {
        return ResponseEntity.ok(proxy.statement(id, page, size));
    }
}
