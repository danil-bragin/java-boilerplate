package com.acme.bank.antifraud.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "screening_decision")
class ScreeningDecisionJpaEntity {

    @Id
    @Column(name = "transfer_id")
    private String transferId;

    @Column(name = "source_account_id", nullable = false)
    private String sourceAccountId;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    @Column(name = "reason")
    private String reason;

    protected ScreeningDecisionJpaEntity() {}

    ScreeningDecisionJpaEntity(String transferId, String sourceAccountId, boolean approved, String reason) {
        this.transferId = transferId;
        this.sourceAccountId = sourceAccountId;
        this.approved = approved;
        this.reason = reason;
    }

    String getTransferId() {
        return transferId;
    }

    String getSourceAccountId() {
        return sourceAccountId;
    }

    boolean isApproved() {
        return approved;
    }

    String getReason() {
        return reason;
    }
}
