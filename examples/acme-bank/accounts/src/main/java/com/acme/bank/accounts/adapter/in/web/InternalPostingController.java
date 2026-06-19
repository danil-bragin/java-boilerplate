package com.acme.bank.accounts.adapter.in.web;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.PostTransferRequest;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.PostTransferResultView;
import com.acme.bank.accounts.adapter.in.web.AccountDtos.PostingStatusView;
import com.acme.bank.accounts.application.PostTransferCommand;
import com.acme.bank.accounts.application.PostTransferResult;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.money.Assets;
import com.acme.money.Money;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, service-to-service surface exposing the <em>money source of truth</em> for a transfer.
 *
 * <ul>
 *   <li>{@code GET /internal/postings/{id}} (BANK-12) — whether a posting exists in the ledger. The
 *       transfers saga reconciler uses it to safely decide whether a transfer stuck in {@code POSTING}
 *       actually moved money.
 *   <li>{@code POST /internal/postings} (BANK-22) — synchronously post a transfer for the transfers
 *       fast-path. Routes to the EXISTING {@link PostTransferCommand}/{@code PostTransferHandler} via
 *       the pipeline — the SAME money mutation as the async saga (lock + Σ=0 + posting-PK anchor),
 *       NOT a duplicate. Idempotent by {@code transferId}: a repeat POST returns POSTED without a
 *       second posting (the anchor dedups).
 * </ul>
 *
 * <p>Returns {@code 200 {transferId, posted}} (GET) / {@code 200 {transferId, status, reason}} (POST)
 * rather than a 404/error, so a caller can distinguish "definitely rejected" from a transport error.
 *
 * <p><strong>Deployment note:</strong> {@code /internal/**} is network-internal and MUST be
 * network-restricted in deployment. It is NOT exposed at the gateway edge (the gateway proxies only
 * {@code /v1/**}); compose does not publish accounts to the host — only the gateway is published —
 * so this endpoint is reachable only on the service network.
 */
@RestController
@RequestMapping("/internal/postings")
public class InternalPostingController {

    private final Ledger ledger;
    private final Pipeline pipeline;

    public InternalPostingController(Ledger ledger, Pipeline pipeline) {
        this.ledger = ledger;
        this.pipeline = pipeline;
    }

    @GetMapping("/{transferId}")
    public PostingStatusView status(@PathVariable String transferId) {
        return new PostingStatusView(transferId, ledger.existsByTransferId(transferId));
    }

    @PostMapping
    public PostTransferResultView post(@Valid @RequestBody PostTransferRequest request) {
        Money amount =
                Money.of(request.amount().value(), Assets.of(request.amount().asset()));
        PostTransferResult result = pipeline.send(new PostTransferCommand(
                request.transferId(), request.sourceAccountId(), request.destinationAccountId(), amount));
        return new PostTransferResultView(
                result.transferId(), result.posted() ? "POSTED" : "REJECTED", result.reason());
    }
}
