package com.acme.bank.gateway.client;

import com.acme.bank.gateway.api.dto.CreateTransferRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resilience4j-guarded {@link RestClient} adapter to the downstream transfers service. A
 * {@code @CircuitBreaker} + {@code @Retry} (instance {@code transfers}) wrap the call; when the
 * circuit is open or retries are exhausted (5xx/timeouts) the fallback raises
 * {@link GatewayUnavailableException}, which the problem+json handler renders as 503.
 *
 * <p>A downstream 4xx ({@link org.springframework.web.client.HttpClientErrorException}) is listed in
 * the instance's {@code ignore-exceptions}, so it is neither retried nor counted toward opening the
 * circuit, and it propagates unchanged to be rendered as the corresponding 4xx by
 * {@link com.acme.bank.gateway.web.DownstreamErrorHandler} — never masked as a 503.
 */
@Component
public class RestTransfersClient implements TransfersRestClient {

    private final RestClient http;

    public RestTransfersClient(RestClient transfersRestClientHttp) {
        this.http = transfersRestClientHttp;
    }

    @Override
    @CircuitBreaker(name = "transfers", fallbackMethod = "createFallback")
    @Retry(name = "transfers")
    public String create(CreateTransferRequest request, String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "sourceAccountId", request.getSourceAccountId(),
                "destinationAccountId", request.getDestinationAccountId(),
                "amount", request.getAmount().getValue(),
                "asset", request.getAmount().getAsset());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = http.post()
                .uri("/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .body(Map.class);

        return response == null ? null : String.valueOf(response.get("transferId"));
    }

    @SuppressWarnings("unused")
    private String createFallback(CreateTransferRequest request, String idempotencyKey, Throwable cause) {
        throw new GatewayUnavailableException(cause);
    }
}
