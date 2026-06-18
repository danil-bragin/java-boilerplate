package com.acme.bank.antifraud.adapter.in.messaging;

import com.acme.bank.antifraud.application.ScreenTransfer;
import com.acme.bank.antifraud.domain.TransferScreened;
import com.acme.bank.contracts.MoneyMapper;
import com.acme.messaging.Inbox;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Consumes Avro {@code TransferRequested} from topic {@code transfer-requested}, deduplicates via Inbox, and screens. */
@Component
public class TransferRequestedListener {

    private final Inbox inbox;
    private final ScreenTransfer screenTransfer;
    private final ApplicationEventPublisher eventPublisher;

    public TransferRequestedListener(
            Inbox inbox, ScreenTransfer screenTransfer, ApplicationEventPublisher eventPublisher) {
        this.inbox = inbox;
        this.screenTransfer = screenTransfer;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = "transfer-requested", groupId = "antifraud")
    @Transactional
    public void onTransferRequested(com.acme.bank.contracts.avro.TransferRequested event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime("antifraud", transferId)) {
            return;
        }
        String sourceAccountId = event.getSourceAccountId().toString();
        com.acme.money.Money amount = MoneyMapper.fromAvro(event.getAmount());
        TransferScreened screened = screenTransfer.screen(transferId, sourceAccountId, amount);
        eventPublisher.publishEvent(screened);
    }
}
