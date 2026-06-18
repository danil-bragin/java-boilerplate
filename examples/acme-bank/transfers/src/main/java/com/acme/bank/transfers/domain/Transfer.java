package com.acme.bank.transfers.domain;

import com.acme.money.Money;
import java.util.Objects;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

/** Coordinating saga aggregate for one money transfer. Enforces the legal state transitions. */
@AggregateRoot
public class Transfer {

    @Identity
    private final TransferId id;

    private final String sourceAccountId;
    private final String destinationAccountId;
    private final Money amount;
    private final String requestedBy;
    private TransferStatus status;
    private String failureReason;

    private Transfer(
            TransferId id, String source, String destination, Money amount, String requestedBy, TransferStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceAccountId = Objects.requireNonNull(source, "source");
        this.destinationAccountId = Objects.requireNonNull(destination, "destination");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.status = status;
    }

    public static Transfer request(TransferId id, String source, String destination, Money amount, String requestedBy) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }
        return new Transfer(id, source, destination, amount, requestedBy, TransferStatus.REQUESTED);
    }

    /**
     * Reconstructs a {@code Transfer} from its persisted state. Used by the JPA adapter to rehydrate
     * aggregates that have already advanced past {@code REQUESTED}.
     */
    public static Transfer rehydrate(
            TransferId id,
            String source,
            String destination,
            Money amount,
            String requestedBy,
            TransferStatus status,
            String failureReason) {
        Transfer t = new Transfer(id, source, destination, amount, requestedBy, status);
        t.failureReason = failureReason;
        return t;
    }

    public TransferRequested toRequestedEvent() {
        return new TransferRequested(id.value(), sourceAccountId, destinationAccountId, amount, requestedBy);
    }

    public void approve() {
        requireStatus(TransferStatus.REQUESTED, TransferStatus.SCREENING);
        this.status = TransferStatus.APPROVED;
    }

    public void reject(String reason) {
        requireStatus(TransferStatus.REQUESTED, TransferStatus.SCREENING);
        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
    }

    public void markPosting() {
        requireStatus(TransferStatus.APPROVED);
        this.status = TransferStatus.POSTING;
    }

    public void complete() {
        requireStatus(TransferStatus.POSTING);
        this.status = TransferStatus.COMPLETED;
    }

    public void fail(String reason) {
        requireStatus(TransferStatus.POSTING, TransferStatus.APPROVED);
        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
    }

    private void requireStatus(TransferStatus... allowed) {
        for (TransferStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new IllegalStateException("illegal transition from " + status);
    }

    public TransferId id() {
        return id;
    }

    public TransferStatus status() {
        return status;
    }

    public Money amount() {
        return amount;
    }

    public String sourceAccountId() {
        return sourceAccountId;
    }

    public String destinationAccountId() {
        return destinationAccountId;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public String failureReason() {
        return failureReason;
    }
}
