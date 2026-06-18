package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.persistence.MoneyAmount;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ledger_entry")
class LedgerEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entry_seq")
    @SequenceGenerator(name = "ledger_entry_seq", sequenceName = "ledger_entry_seq", allocationSize = 50)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private String transferId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Embedded
    private MoneyAmount amount;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected LedgerEntryJpaEntity() {}

    LedgerEntryJpaEntity(String transferId, String accountId, MoneyAmount amount, Instant postedAt) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.amount = amount;
        this.postedAt = postedAt;
    }

    String getTransferId() {
        return transferId;
    }

    String getAccountId() {
        return accountId;
    }

    MoneyAmount getAmount() {
        return amount;
    }

    Instant getPostedAt() {
        return postedAt;
    }
}
