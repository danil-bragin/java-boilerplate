package com.acme.bank.gateway.projection;

import com.acme.bank.contracts.MoneyMapper;
import com.acme.messaging.Inbox;
import com.acme.persistence.MoneyAmount;
import java.time.Instant;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@link TransferView} CQRS read model by consuming the saga's Avro events. Each listener
 * is inbox-deduped (one {@code processed_messages} row per {@code (listener, transferId)}) and the
 * upsert is rank-guarded so redelivered or out-of-order events never regress a transfer's status.
 *
 * <p>This is a read-model projection, not a money optimization: the gateway holds no balances and
 * enforces no money invariants — those live in the owning services.
 */
@Component
public class TransferStatusProjection {

    private static final String LISTENER_REQUESTED = "gateway-projection:transfer-requested";
    private static final String LISTENER_SCREENED = "gateway-projection:transfer-screened";
    private static final String LISTENER_COMPLETED = "gateway-projection:transfer-completed";
    private static final String LISTENER_FAILED = "gateway-projection:transfer-failed";

    private final Inbox inbox;
    private final TransferViewRepository repository;

    public TransferStatusProjection(Inbox inbox, TransferViewRepository repository) {
        this.inbox = inbox;
        this.repository = repository;
    }

    @KafkaListener(topics = "transfer-requested", groupId = "gateway-projection-requested")
    @Transactional
    public void onTransferRequested(com.acme.bank.contracts.avro.TransferRequested event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime(LISTENER_REQUESTED, transferId)) {
            return;
        }
        Instant now = Instant.now();
        if (repository.existsById(transferId)) {
            return;
        }
        MoneyAmount amount = MoneyAmount.from(MoneyMapper.fromAvro(event.getAmount()));
        TransferView view = new TransferView(
                transferId,
                TransferStatus.REQUESTED,
                amount,
                event.getSourceAccountId().toString(),
                event.getDestinationAccountId().toString(),
                now);
        repository.save(view);
    }

    @KafkaListener(topics = "transfer-screened", groupId = "gateway-projection-screened")
    @Transactional
    public void onTransferScreened(com.acme.bank.contracts.avro.TransferScreened event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime(LISTENER_SCREENED, transferId)) {
            return;
        }
        TransferStatus next = event.getApproved() ? TransferStatus.APPROVED : TransferStatus.FAILED;
        String reason = event.getApproved() || event.getReason() == null
                ? null
                : event.getReason().toString();
        advance(transferId, next, reason);
    }

    @KafkaListener(topics = "transfer-completed", groupId = "gateway-projection-completed")
    @Transactional
    public void onTransferCompleted(com.acme.bank.contracts.avro.TransferCompleted event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime(LISTENER_COMPLETED, transferId)) {
            return;
        }
        advance(transferId, TransferStatus.COMPLETED, null);
    }

    @KafkaListener(topics = "transfer-failed", groupId = "gateway-projection-failed")
    @Transactional
    public void onTransferFailed(com.acme.bank.contracts.avro.TransferFailed event) {
        String transferId = event.getTransferId().toString();
        if (!inbox.firstTime(LISTENER_FAILED, transferId)) {
            return;
        }
        advance(transferId, TransferStatus.FAILED, event.getReason().toString());
    }

    private void advance(String transferId, TransferStatus next, String failureReason) {
        repository.findById(transferId).ifPresent(view -> view.advanceTo(next, failureReason, Instant.now()));
    }
}
