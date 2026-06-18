package com.acme.bank.transfers.adapter.out.reconcile;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resilience4j-guarded {@link RestClient} to the accounts <em>money source of truth</em>
 * ({@code GET /internal/postings/{id}} → {@code {transferId, posted}}). Instance
 * {@code accounts-reconcile}.
 *
 * <p>Returns {@code Optional<Boolean>}:
 *
 * <ul>
 *   <li>{@code Optional.of(true)} — accounts confirms a posting exists (money moved);
 *   <li>{@code Optional.of(false)} — accounts confirms NO posting (money did not move);
 *   <li>{@code Optional.empty()} — a transport failure (5xx / timeout / open circuit). The
 *       reconciler treats this as "skip this round" and NEVER fails a transfer on a failed query —
 *       this is the money-safety guarantee: a POSTING transfer is failed only after a confirmed
 *       {@code false}, never on the absence of an answer.
 * </ul>
 */
@Component
public class AccountsPostingClient {

    private final RestClient http;

    public AccountsPostingClient(RestClient accountsReconcileRestClient) {
        this.http = accountsReconcileRestClient;
    }

    @CircuitBreaker(name = "accounts-reconcile", fallbackMethod = "postedFallback")
    @Retry(name = "accounts-reconcile")
    public Optional<Boolean> posted(String transferId) {
        PostingStatusView view =
                http.get().uri("/internal/postings/{id}", transferId).retrieve().body(PostingStatusView.class);
        return view == null ? Optional.empty() : Optional.of(view.posted());
    }

    @SuppressWarnings("unused")
    private Optional<Boolean> postedFallback(String transferId, Throwable cause) {
        // Transport failure: return empty so the reconciler skips this round (money-safety).
        return Optional.empty();
    }

    /** Response payload of the accounts internal posting-status query. */
    record PostingStatusView(String transferId, boolean posted) {}
}
