package com.acme.bank.transfers.adapter.in.messaging;

import com.acme.bank.transfers.domain.PostingRequestedEvent;
import com.acme.bank.transfers.domain.Transfer;
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
 * Consumes Avro {@code TransferScreened} from topic {@code transfer-screened}, deduplicates via Inbox,
 * and advances the transfer saga: approved → POSTING; rejected → FAILED.
 *
 * <p>BANK-21: a BATCH listener (see {@code BatchListenerConfig}). Advancing the saga moves NO money (the
 * money POSTING lives in accounts), so a poll of N screening results is applied in ONE transaction —
 * amortizing the commit/WAL-fsync across the batch. Per-record inbox dedup is preserved inside the loop, so
 * a redelivered result still skips an already-advanced transfer and never double-advances. Records arrive
 * in offset order, keyed by transferId, so per-transfer ordering and the state-machine rank guards hold.
 *
 * <p>Partial-failure semantics: a per-record BUSINESS condition (unknown transfer, or an illegal/duplicate
 * transition that the inbox didn't already absorb) is surfaced as {@link BatchListenerFailedException} for
 * THAT record — the container commits the offsets of the records processed before it and re-drives from it;
 * the inbox makes the redrive idempotent, and the messaging starter's error-handler routes a true poison to
 * the DLT after its backoff. The same mechanism handles a true infra error mid-batch (commit-up-to-i, retry
 * from i). A committed sibling earlier in the batch is therefore never lost or re-applied.
 */
@Component
public class ScreeningResultListener {

    private final Inbox inbox;
    private final Transfers transfers;
    private final ApplicationEventPublisher eventPublisher;

    public ScreeningResultListener(Inbox inbox, Transfers transfers, ApplicationEventPublisher eventPublisher) {
        this.inbox = inbox;
        this.transfers = transfers;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(
            topics = "transfer-screened",
            groupId = "transfers",
            containerFactory = "batchListenerContainerFactory")
    @Transactional
    public void onTransferScreened(
            List<ConsumerRecord<String, com.acme.bank.contracts.avro.TransferScreened>> records) {
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, com.acme.bank.contracts.avro.TransferScreened> record = records.get(i);
            com.acme.bank.contracts.avro.TransferScreened event = record.value();
            String transferId = event.getTransferId().toString();
            if (!inbox.firstTime("transfers-screening", transferId)) {
                continue;
            }
            try {
                Transfer transfer = transfers
                        .findById(new TransferId(transferId))
                        .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));

                if (event.getApproved()) {
                    transfer.approve();
                    transfer.markPosting();
                    transfers.save(transfer);
                    eventPublisher.publishEvent(new PostingRequestedEvent(
                            transferId,
                            transfer.sourceAccountId(),
                            transfer.destinationAccountId(),
                            transfer.amount()));
                } else {
                    String reason =
                            event.getReason() != null ? event.getReason().toString() : "REJECTED";
                    transfer.reject(reason);
                    transfers.save(transfer);
                    eventPublisher.publishEvent(new TransferFailedEvent(transferId, reason));
                }
            } catch (RuntimeException e) {
                // Surface this single record to the error handler: it commits the offsets of the records
                // already advanced in this batch and re-drives from this one. The inbox row for this
                // transfer was written above in the (now rolled-back) tx, so the redrive sees firstTime=true
                // again and re-attempts cleanly; a true poison reaches the DLT after the backoff.
                throw new BatchListenerFailedException("transfer-screened record failed: " + transferId, e, i);
            }
        }
    }
}
