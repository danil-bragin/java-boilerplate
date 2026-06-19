package com.acme.bank.transfers.adapter.out.persistence;

import com.acme.persistence.MoneyAmount;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "transfer")
class TransferJpaEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "source_account_id", nullable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private String destinationAccountId;

    @Embedded
    private MoneyAmount amount;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Creation timestamp — DB-managed (DEFAULT now()); Hibernate never writes it so an update never
     * resets it. Drives the per-source fast-path velocity count (BANK-22 Fix 1).
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected TransferJpaEntity() {}

    TransferJpaEntity(
            String id,
            String sourceAccountId,
            String destinationAccountId,
            MoneyAmount amount,
            String requestedBy,
            String status,
            String failureReason,
            Instant updatedAt) {
        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.requestedBy = requestedBy;
        this.status = status;
        this.failureReason = failureReason;
        this.updatedAt = updatedAt;
    }

    String getId() {
        return id;
    }

    String getSourceAccountId() {
        return sourceAccountId;
    }

    String getDestinationAccountId() {
        return destinationAccountId;
    }

    MoneyAmount getAmount() {
        return amount;
    }

    String getRequestedBy() {
        return requestedBy;
    }

    String getStatus() {
        return status;
    }

    String getFailureReason() {
        return failureReason;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
