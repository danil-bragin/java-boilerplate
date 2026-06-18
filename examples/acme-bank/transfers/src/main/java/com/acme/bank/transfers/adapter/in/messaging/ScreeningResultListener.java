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
