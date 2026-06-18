package com.acme.bank.accounts.adapter.in.web;

import com.acme.bank.accounts.adapter.in.web.AccountDtos.PostingStatusView;
import com.acme.bank.accounts.domain.Ledger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, service-to-service query exposing the <em>money source of truth</em> for a transfer:
 * whether a posting exists in the ledger. The transfers saga reconciler uses this to safely decide
 * whether a transfer stuck in {@code POSTING} actually moved money — completing it when the ledger
 * confirms a posting, and only timing it out (FAILED) when the ledger confirms none.
 *
 * <p>Returns {@code 200 {transferId, posted}} with a boolean rather than a 404, so a caller can
 * distinguish "definitely not posted" (a real {@code false}) from a transport error (no response at
 * all → the reconciler skips the round and never fails money on a failed query).
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

    public InternalPostingController(Ledger ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/{transferId}")
    public PostingStatusView status(@PathVariable String transferId) {
        return new PostingStatusView(transferId, ledger.existsByTransferId(transferId));
    }
}
