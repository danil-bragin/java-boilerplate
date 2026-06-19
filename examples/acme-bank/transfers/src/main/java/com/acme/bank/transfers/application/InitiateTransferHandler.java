package com.acme.bank.transfers.application;

import an.awesome.pipelinr.Command;
import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient;
import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient.SyncPostRequest;
import com.acme.bank.transfers.adapter.out.posting.SyncPostResult;
import com.acme.bank.transfers.domain.PostingRequestedEvent;
import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferCompletedEvent;
import com.acme.bank.transfers.domain.TransferFailedEvent;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Initiates a transfer. Two paths share ONE money mutation (the accounts posting — lock + Σ=0 +
 * posting-PK anchor — which is NEVER changed here):
 *
 * <ul>
 *   <li><b>Slow-path (unchanged):</b> ineligible (large) or flag-off transfers are persisted
 *       {@code REQUESTED} and a {@code TransferRequested} event kicks the 6-hop async saga. Returns
 *       {@code REQUESTED} (→ 202).
 *   <li><b>Fast-path (BANK-22):</b> an eligible (small, auto-approvable) transfer is inline-approved
 *       (REQUESTED→APPROVED→POSTING), persisted {@code POSTING} BEFORE a SYNCHRONOUS post to accounts,
 *       then terminalized by the answer:
 *       <ul>
 *         <li>POSTED → {@code complete()} → {@code TransferCompletedEvent} → COMPLETED (200).
 *         <li>REJECTED → {@code fail(reason)} → {@code TransferFailedEvent} → FAILED (200).
 *         <li>NOT_MADE (circuit open / connection refused — NO posting happened) → re-emit
 *             {@code PostingRequestedEvent} so the async saga drives the posting; stays POSTING (202).
 *             Safe: nothing posted, and the re-drive is idempotent (accounts anchor dedups).
 *         <li>UNKNOWN (post-send timeout — whether accounts posted is UNKNOWN) → do NOT guess: leave
 *             the transfer {@code POSTING} (202) for the BANK-12 reconciler, which queries accounts'
 *             ledger and completes-or-re-drives. NEVER complete/fail here (would double-post or strand).
 *       </ul>
 * </ul>
 *
 * <p>Why persist POSTING BEFORE the sync call: if the process dies mid-call, the reconciler finds a
 * POSTING row and reconciles it against the ledger — the same recovery as the async path. The status
 * update and the published event commit atomically in the {@code StronglyConsistent} tx (outbox).
 */
@Component
@EnableConfigurationProperties(FastPathProperties.class)
public class InitiateTransferHandler implements Command.Handler<InitiateTransferCommand, InitiateTransferResult> {

    private static final Logger log = LoggerFactory.getLogger(InitiateTransferHandler.class);

    private final Transfers transfers;
    private final ApplicationEventPublisher events;
    private final FastPathProperties fastPath;
    private final AccountsPostingSyncClient syncClient;

    public InitiateTransferHandler(
            Transfers transfers,
            ApplicationEventPublisher events,
            FastPathProperties fastPath,
            AccountsPostingSyncClient syncClient) {
        this.transfers = transfers;
        this.events = events;
        this.fastPath = fastPath;
        this.syncClient = syncClient;
    }

    @Override
    public InitiateTransferResult handle(InitiateTransferCommand command) {
        TransferId id = new TransferId(command.transferId());
        if (transfers.exists(id)) {
            // Idempotent initiate: a re-attempt with the same transferId resolves to the existing row.
            return transfers
                    .findById(id)
                    .map(t -> new InitiateTransferResult(t.id().value(), t.status(), t.failureReason()))
                    .orElseGet(() -> InitiateTransferResult.requested(id.value()));
        }

        Transfer transfer = Transfer.request(
                id, command.sourceAccountId(), command.destinationAccountId(), command.amount(), command.requestedBy());

        if (fastPath.isEligible(command.amount())) {
            return fastPath(command, transfer);
        }

        // Slow-path (unchanged): persist REQUESTED, kick the async saga.
        transfers.save(transfer);
        events.publishEvent(transfer.toRequestedEvent());
        return InitiateTransferResult.requested(id.value());
    }

    private InitiateTransferResult fastPath(InitiateTransferCommand command, Transfer transfer) {
        String id = transfer.id().value();
        // Inline-approve and persist POSTING BEFORE the sync call (critical for the reconciler edge):
        // a crash mid-call leaves a POSTING row the reconciler resolves against accounts' ledger.
        transfer.approve();
        transfer.markPosting();
        transfers.save(transfer);

        SyncPostResult result = syncClient.post(
                new SyncPostRequest(id, command.sourceAccountId(), command.destinationAccountId(), command.amount()));

        switch (result.outcome()) {
            case POSTED -> {
                transfer.complete();
                transfers.save(transfer);
                events.publishEvent(new TransferCompletedEvent(id));
                return InitiateTransferResult.completed(id);
            }
            case REJECTED -> {
                transfer.fail(result.reason());
                transfers.save(transfer);
                events.publishEvent(new TransferFailedEvent(id, result.reason()));
                return InitiateTransferResult.failed(id, result.reason());
            }
            case NOT_MADE -> {
                // Circuit open / connection refused: no posting happened. Hand off to the async saga by
                // re-emitting posting-requested (idempotent — accounts anchor dedups). Stays POSTING.
                log.info("fast-path: accounts not reachable for {} — falling back to async posting", id);
                events.publishEvent(new PostingRequestedEvent(
                        id, command.sourceAccountId(), command.destinationAccountId(), command.amount()));
                return InitiateTransferResult.posting(id);
            }
            default -> {
                // UNKNOWN (post-send timeout): whether accounts posted is UNKNOWN. Do NOT guess. Leave the
                // transfer POSTING (already persisted) for the BANK-12 reconciler to resolve against the
                // ledger. NEVER complete/fail here — that risks a double-post or stranded money.
                log.warn("fast-path: UNKNOWN posting outcome for {} — leaving POSTING for reconciler", id);
                return InitiateTransferResult.posting(id);
            }
        }
    }
}
