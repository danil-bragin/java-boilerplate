package com.acme.bank.transfers.adapter.out.persistence;

import com.acme.persistence.MoneyAmount;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    protected TransferJpaEntity() {}

    TransferJpaEntity(
            String id,
            String sourceAccountId,
            String destinationAccountId,
            MoneyAmount amount,
            String requestedBy,
            String status,
            String failureReason) {
        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.requestedBy = requestedBy;
        this.status = status;
        this.failureReason = failureReason;
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
}
