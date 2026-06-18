package com.acme.bank.antifraud.adapter.in.messaging;

import com.acme.bank.antifraud.application.ScreenTransfer;
import com.acme.bank.antifraud.domain.TransferScreened;
import com.acme.bank.contracts.MoneyMapper;
import com.acme.messaging.Inbox;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes Avro {@code TransferRequested} from topic {@code transfer-requested}, deduplicates via Inbox,
 * and screens.
 *
 * <p>BANK-19b: a BATCH listener (see {@code BatchListenerConfig}). Screening is an idempotent, NON-money
 * hop, so a poll of N records is screened in ONE transaction — amortizing the commit/WAL-fsync across the
 * batch. Per-record dedup is preserved: each record is inbox-guarded inside the loop, so redelivery still
 * skips already-screened transfers and a duplicate inside the batch is a no-op. Records arrive in offset
 * order and keying by transferId is unchanged, so per-transfer ordering holds. There is NO money
 * partial-failure risk here — a screening decision moves no money (the accounts POSTING path, which does,
 * stays per-posting and is never batched).
 */
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

    @KafkaListener(
            topics = "transfer-requested",
            groupId = "antifraud",
            containerFactory = "batchListenerContainerFactory")
    @Transactional
    public void onTransferRequested(List<com.acme.bank.contracts.avro.TransferRequested> events) {
        for (com.acme.bank.contracts.avro.TransferRequested event : events) {
            String transferId = event.getTransferId().toString();
            if (!inbox.firstTime("antifraud", transferId)) {
                continue;
            }
            String sourceAccountId = event.getSourceAccountId().toString();
            com.acme.money.Money amount = MoneyMapper.fromAvro(event.getAmount());
            TransferScreened screened = screenTransfer.screen(transferId, sourceAccountId, amount);
            eventPublisher.publishEvent(screened);
        }
    }
}
