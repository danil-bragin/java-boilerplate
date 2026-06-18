package com.acme.bank.gateway.projection;

import com.acme.persistence.MoneyAmount;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** CQRS read model row for a transfer, built by consuming the saga's Avro events (rank-guarded). */
@Entity
@Table(name = "transfer_view")
public class TransferView {

    @Id
    @Column(name = "transfer_id")
    private String transferId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "status_rank", nullable = false)
    private int statusRank;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "amount_value", nullable = false)),
        @AttributeOverride(name = "asset", column = @Column(name = "amount_asset", nullable = false))
    })
    private MoneyAmount amount;

    @Column(name = "source_account_id", nullable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private String destinationAccountId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TransferView() {
        // for JPA
    }

    public TransferView(
            String transferId,
            TransferStatus status,
            MoneyAmount amount,
            String sourceAccountId,
            String destinationAccountId,
            Instant now) {
        this.transferId = transferId;
        this.status = status.name();
        this.statusRank = status.rank();
        this.amount = amount;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Advance to {@code newStatus} only when it does not regress the rank. Returns {@code true} when
     * the status actually changed. Stale/out-of-order events (lower rank) are ignored.
     */
    public boolean advanceTo(TransferStatus newStatus, String failureReason, Instant now) {
        if (newStatus.rank() < this.statusRank) {
            return false;
        }
        this.status = newStatus.name();
        this.statusRank = newStatus.rank();
        this.failureReason = failureReason;
        this.updatedAt = now;
        return true;
    }

    public String transferId() {
        return transferId;
    }

    public String status() {
        return status;
    }

    public MoneyAmount amount() {
        return amount;
    }

    public String sourceAccountId() {
        return sourceAccountId;
    }

    public String destinationAccountId() {
        return destinationAccountId;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
