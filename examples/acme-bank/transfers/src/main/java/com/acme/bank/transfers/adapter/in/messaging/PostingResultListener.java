package com.acme.bank.transfers.adapter.in.messaging;

import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferCompletedEvent;
import com.acme.bank.transfers.domain.TransferFailedEvent;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.messaging.Inbox;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes Avro {@code LedgerPosted} and {@code PostingRejected} from their respective topics,
 * deduplicates via Inbox, and completes or fails the transfer saga.
 *
 * <p>BANK-21: BATCH listeners (see {@code BatchListenerConfig}). Completing/failing a transfer moves NO
 * money — accounts already terminalized the ledger; this only records the saga's terminal status and emits
 * the terminal saga event. So a poll of N results is applied in ONE transaction, amortizing the commit.
 * Per-record inbox dedup is kept inside the loop, so a redelivered result never double-terminalizes;
 * records arrive in offset order keyed by transferId, preserving per-transfer ordering and the state guards.
 *
 * <p>Partial-failure: a per-record condition (unknown transfer, illegal transition not absorbed by the
 * inbox, or a true infra error) is surfaced as {@link BatchListenerFailedException} for that record — the
 * container commits the offsets of the records already terminalized in this batch and re-drives from the
 * failing one (inbox-idempotent; a true poison reaches the DLT after backoff). Any saga progress that a
 * rolled-back partial commit drops is re-driven by the money-safe {@code SagaReconciler}; every consumer is
 * idempotent (inbox + posting-PK anchor), so no money guarantee depends on the batch commit boundary.
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

    @KafkaListener(topics = "ledger-posted", groupId = "transfers", containerFactory = "batchListenerContainerFactory")
    @Transactional
    public void onLedgerPosted(List<ConsumerRecord<String, com.acme.bank.contracts.avro.LedgerPosted>> records) {
        for (int i = 0; i < records.size(); i++) {
            com.acme.bank.contracts.avro.LedgerPosted event = records.get(i).value();
            String transferId = event.getTransferId().toString();
            if (!inbox.firstTime("transfers-ledger-posted", transferId)) {
                continue;
            }
            try {
                Transfer transfer = transfers
                        .findById(new TransferId(transferId))
                        .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
                transfer.complete();
                transfers.save(transfer);
                eventPublisher.publishEvent(new TransferCompletedEvent(transferId));
            } catch (RuntimeException e) {
                throw new BatchListenerFailedException("ledger-posted record failed: " + transferId, e, i);
            }
        }
    }

    @KafkaListener(
            topics = "posting-rejected",
            groupId = "transfers",
            containerFactory = "batchListenerContainerFactory")
    @Transactional
    public void onPostingRejected(List<ConsumerRecord<String, com.acme.bank.contracts.avro.PostingRejected>> records) {
        for (int i = 0; i < records.size(); i++) {
            com.acme.bank.contracts.avro.PostingRejected event = records.get(i).value();
            String transferId = event.getTransferId().toString();
            if (!inbox.firstTime("transfers-posting-rejected", transferId)) {
                continue;
            }
            try {
                Transfer transfer = transfers
                        .findById(new TransferId(transferId))
                        .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
                String reason = event.getReason() != null ? event.getReason().toString() : "POSTING_REJECTED";
                transfer.fail(reason);
                transfers.save(transfer);
                eventPublisher.publishEvent(new TransferFailedEvent(transferId, reason));
            } catch (RuntimeException e) {
                throw new BatchListenerFailedException("posting-rejected record failed: " + transferId, e, i);
            }
        }
    }
}
