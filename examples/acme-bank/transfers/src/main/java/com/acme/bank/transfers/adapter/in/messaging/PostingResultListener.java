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
