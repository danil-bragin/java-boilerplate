package com.acme.bank.gateway.client;

import com.acme.bank.gateway.api.dto.AccountView;
import com.acme.bank.gateway.api.dto.Money;
import com.acme.bank.gateway.api.dto.OpenAccountRequest;
import com.acme.bank.gateway.api.dto.StatementPage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resilience4j-guarded {@link RestClient} adapter to the downstream accounts service (instance
 * {@code accounts}). On 5xx/timeouts/open-circuit the fallback raises
 * {@link GatewayUnavailableException} (rendered 503); a downstream 4xx is ignored by the instance and
 * propagates unchanged to {@link com.acme.bank.gateway.web.DownstreamErrorHandler} (rendered as the
 * same 4xx) — never masked as a 503. Mirrors {@link RestTransfersClient}.
 */
@Component
public class RestAccountsClient implements AccountsRestClient {

    private final RestClient http;

    public RestAccountsClient(RestClient accountsRestClientHttp) {
        this.http = accountsRestClientHttp;
    }

    @Override
    @CircuitBreaker(name = "accounts", fallbackMethod = "openFallback")
    @Retry(name = "accounts")
    public AccountView open(OpenAccountRequest request, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerName", request.getOwnerName());
        body.put("asset", request.getAsset());
        if (request.getInitialDeposit() != null) {
            body.put(
                    "initialDeposit",
                    Map.of(
                            "value", request.getInitialDeposit().getValue(),
                            "asset", request.getInitialDeposit().getAsset()));
        }
        return http.post()
                .uri("/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .body(AccountView.class);
    }

    @Override
    @CircuitBreaker(name = "accounts", fallbackMethod = "getFallback")
    @Retry(name = "accounts")
    public AccountView get(String id) {
        return http.get().uri("/v1/accounts/{id}", id).retrieve().body(AccountView.class);
    }

    @Override
    @CircuitBreaker(name = "accounts", fallbackMethod = "balanceFallback")
    @Retry(name = "accounts")
    public Money balance(String id) {
        return http.get().uri("/v1/accounts/{id}/balance", id).retrieve().body(Money.class);
    }

    @Override
    @CircuitBreaker(name = "accounts", fallbackMethod = "statementFallback")
    @Retry(name = "accounts")
    public StatementPage statement(String id, Integer page, Integer size) {
        String uri = UriComponentsBuilder.fromPath("/v1/accounts/{id}/statement")
                .queryParam("page", page)
                .queryParam("size", size)
                .buildAndExpand(id)
                .toUriString();
        return http.get().uri(uri).retrieve().body(StatementPage.class);
    }

    @SuppressWarnings("unused")
    private AccountView openFallback(OpenAccountRequest request, String idempotencyKey, Throwable cause) {
        throw new GatewayUnavailableException(cause);
    }

    @SuppressWarnings("unused")
    private AccountView getFallback(String id, Throwable cause) {
        throw new GatewayUnavailableException(cause);
    }

    @SuppressWarnings("unused")
    private Money balanceFallback(String id, Throwable cause) {
        throw new GatewayUnavailableException(cause);
    }

    @SuppressWarnings("unused")
    private StatementPage statementFallback(String id, Integer page, Integer size, Throwable cause) {
        throw new GatewayUnavailableException(cause);
    }
}
