package com.acme.bank.transfers.adapter.in.messaging;

import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferCompletedEvent;
import com.acme.bank.transfers.domain.TransferFailedEvent;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.messaging.Inbox;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes Avro {@code LedgerPosted} and {@code PostingRejected} from their respective topics,
 * deduplicates via Inbox, and completes or fails the transfer saga.
 *
 * <p>Processed PER RECORD: one record = one {@code @Transactional} invocation = its own tx and its own
 * committed offset. (BANK-21 batched these in one tx with {@code BatchListenerFailedException} on a
 * non-transactional container, which could commit offsets for records whose DB tx had been rolled back by a
 * mid-batch infra error — a latent loss bug. Reverted to per-record so tx and offset move together.) The
 * inbox dedups redeliveries so a result never double-terminalizes; a true infra error rolls back this
 * single record's tx and re-drives it idempotently.
 */
@Component
public class PostingResultListener {

    private final Inbox inbox;
    private final Transfers transfers;
    private final ApplicationEventPublisher eventPublisher;

    public PostingResultListener(Inbox inbox, Transfers transfers, ApplicationEventPublisher eventPublisher) {
        this.inbox = inbox;
        this.transfers = transfers;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = "ledger-posted", groupId = "transfers")
    @Transactional
    public void onLedgerPosted(com.acme.bank.contracts.avro.LedgerPosted event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime("transfers-ledger-posted", transferId)) {
            return;
        }
        Transfer transfer = transfers
                .findById(new TransferId(transferId))
                .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
        transfer.complete();
        transfers.save(transfer);
        eventPublisher.publishEvent(new TransferCompletedEvent(transferId));
    }

    @KafkaListener(topics = "posting-rejected", groupId = "transfers")
    @Transactional
    public void onPostingRejected(com.acme.bank.contracts.avro.PostingRejected event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime("transfers-posting-rejected", transferId)) {
            return;
        }
        Transfer transfer = transfers
                .findById(new TransferId(transferId))
                .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
        String reason = event.getReason() != null ? event.getReason().toString() : "POSTING_REJECTED";
        transfer.fail(reason);
        transfers.save(transfer);
        eventPublisher.publishEvent(new TransferFailedEvent(transferId, reason));
    }
}
