package com.acme.bank.notifications.adapter.in.messaging;

import com.acme.bank.notifications.application.NotifyOnTransfer;
import com.acme.messaging.Inbox;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes terminal transfer events ({@code transfer-completed}, {@code transfer-failed}),
 * deduplicates via Inbox, and triggers mock-delivery notifications.
 */
@Component
public class TransferTerminalEventListener {

    private final Inbox inbox;
    private final NotifyOnTransfer notifyOnTransfer;

    public TransferTerminalEventListener(Inbox inbox, NotifyOnTransfer notifyOnTransfer) {
        this.inbox = inbox;
        this.notifyOnTransfer = notifyOnTransfer;
    }

    @KafkaListener(topics = "transfer-completed", groupId = "notifications")
    @Transactional
    public void onTransferCompleted(com.acme.bank.contracts.avro.TransferCompleted event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime("notifications-completed", transferId)) {
            return;
        }
        notifyOnTransfer.notifyCompleted(transferId);
    }

    @KafkaListener(topics = "transfer-failed", groupId = "notifications")
    @Transactional
    public void onTransferFailed(com.acme.bank.contracts.avro.TransferFailed event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime("notifications-failed", transferId)) {
            return;
        }
        String reason = event.getReason() != null ? event.getReason().toString() : "UNKNOWN";
        notifyOnTransfer.notifyFailed(transferId, reason);
    }
}
