package com.acme.bank.transfers.adapter.out.posting;

import com.acme.money.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Resilience4j-guarded synchronous money-posting client used by the transfers fast-path. It calls the
 * accounts money authority ({@code POST /internal/postings}) — the SAME {@code PostTransferHandler}
 * (lock + Σ=0 + posting-PK anchor) the async saga drives — and maps the result to a {@link SyncPostResult}.
 *
 * <p>The whole money-safety of the fast-path rests on the fallback correctly distinguishing two
 * failure shapes:
 *
 * <ul>
 *   <li>{@link CallNotPermittedException} — the circuit is OPEN, so resilience4j short-circuited and
 *       the call was <strong>never dispatched</strong>. No posting could have happened →
 *       {@link SyncPostResult#notMade()}. The handler safely falls back to the async saga.
 *   <li>{@link ResourceAccessException} — an I/O failure. After {@code @Retry} is exhausted this is
 *       the post-send timeout case: the bytes may have reached accounts and a posting MAY have
 *       committed, but no answer came back → {@link SyncPostResult#unknown()}. The handler leaves the
 *       transfer {@code POSTING} for the BANK-12 reconciler to resolve against accounts' ledger.
 *   <li>Any other throwable is treated as {@link SyncPostResult#unknown()} — the safe default: when in
 *       doubt about whether money moved, NEVER guess a terminal; let the reconciler decide.
 * </ul>
 *
 * <p>Retrying a timed-out POST is safe: the accounts posting-PK anchor dedups by {@code transferId},
 * so at most one posting exists regardless of how many attempts are made.
 */
@Component
public class AccountsPostingSyncClient {

    private static final Logger log = LoggerFactory.getLogger(AccountsPostingSyncClient.class);

    private final RestClient http;

    public AccountsPostingSyncClient(RestClient accountsPostingSyncRestClient) {
        this.http = accountsPostingSyncRestClient;
    }

    /**
     * The fast-path posting payload. Carries the transferId so accounts anchors the posting (idempotency)
     * and the source/destination/amount for the ledger mutation.
     */
    public record SyncPostRequest(
            String transferId, String sourceAccountId, String destinationAccountId, Money amount) {}

    @CircuitBreaker(name = "accounts-sync", fallbackMethod = "postFallback")
    @Retry(name = "accounts-sync")
    public SyncPostResult post(SyncPostRequest request) {
        Map<String, Object> body = Map.of(
                "transferId", request.transferId(),
                "sourceAccountId", request.sourceAccountId(),
                "destinationAccountId", request.destinationAccountId(),
                "amount",
                        Map.of(
                                "value", request.amount().toAmountString(),
                                "asset", request.amount().asset().code()));

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                http.post().uri("/internal/postings").body(body).retrieve().body(Map.class);

        if (response == null) {
            // 200 with no body shouldn't happen; treat as UNKNOWN rather than guessing a terminal.
            log.warn("accounts-sync: null body for transfer {} — treating as UNKNOWN", request.transferId());
            return SyncPostResult.unknown();
        }
        String status = String.valueOf(response.get("status"));
        if ("POSTED".equals(status)) {
            return SyncPostResult.posted();
        }
        if ("REJECTED".equals(status)) {
            Object reason = response.get("reason");
            return SyncPostResult.rejected(reason == null ? "REJECTED" : String.valueOf(reason));
        }
        // An unrecognized 200 body — do not fabricate a terminal.
        log.warn("accounts-sync: unrecognized status '{}' for transfer {} — UNKNOWN", status, request.transferId());
        return SyncPostResult.unknown();
    }

    @SuppressWarnings("unused")
    private SyncPostResult postFallback(SyncPostRequest request, Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            // Circuit OPEN: the call was never dispatched — no posting happened. Safe async fallback.
            log.info("accounts-sync: circuit open for transfer {} — NOT_MADE (async fallback)", request.transferId());
            return SyncPostResult.notMade();
        }
        if (cause instanceof ResourceAccessException) {
            // I/O failure after the bytes left us: whether accounts posted is UNKNOWN. Leave POSTING.
            log.warn(
                    "accounts-sync: I/O failure for transfer {} ({}) — UNKNOWN, leaving POSTING for reconciler",
                    request.transferId(),
                    cause.toString());
            return SyncPostResult.unknown();
        }
        // Any other failure: be conservative — never guess a terminal over money. Let the reconciler decide.
        log.warn(
                "accounts-sync: unexpected failure for transfer {} ({}) — UNKNOWN (conservative)",
                request.transferId(),
                cause.toString());
        return SyncPostResult.unknown();
    }
}
