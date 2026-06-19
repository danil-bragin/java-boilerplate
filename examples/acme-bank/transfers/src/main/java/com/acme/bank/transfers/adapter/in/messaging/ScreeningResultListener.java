package com.acme.bank.transfers.adapter.in.messaging;

import com.acme.bank.transfers.domain.PostingRequestedEvent;
import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferFailedEvent;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.messaging.Inbox;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes Avro {@code TransferScreened} from topic {@code transfer-screened}, deduplicates via Inbox,
 * and advances the transfer saga: approved → POSTING; rejected → FAILED.
 *
 * <p>Processed PER RECORD: one record = one {@code @Transactional} invocation = its own tx and its own
 * committed offset. (BANK-21 batched this in one tx with {@code BatchListenerFailedException} on a
 * non-transactional container, which could commit offsets for records whose DB tx had been rolled back by a
 * mid-batch infra error — a latent loss bug. Reverted to per-record so tx and offset move together.) The
 * inbox dedups redeliveries, so an already-advanced transfer is skipped and the state-machine rank guards
 * never regress; a true infra error rolls back this single record's tx and re-drives it idempotently.
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

    @KafkaListener(topics = "transfer-screened", groupId = "transfers")
    @Transactional
    public void onTransferScreened(com.acme.bank.contracts.avro.TransferScreened event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime("transfers-screening", transferId)) {
            return;
        }
        Transfer transfer = transfers
                .findById(new TransferId(transferId))
                .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));

        if (event.getApproved()) {
            transfer.approve();
            transfer.markPosting();
            transfers.save(transfer);
            eventPublisher.publishEvent(new PostingRequestedEvent(
                    transferId, transfer.sourceAccountId(), transfer.destinationAccountId(), transfer.amount()));
        } else {
            String reason = event.getReason() != null ? event.getReason().toString() : "REJECTED";
            transfer.reject(reason);
            transfers.save(transfer);
            eventPublisher.publishEvent(new TransferFailedEvent(transferId, reason));
        }
    }
}
