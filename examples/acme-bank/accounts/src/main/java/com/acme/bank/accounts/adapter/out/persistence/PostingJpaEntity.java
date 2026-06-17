package com.acme.bank.accounts.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotency anchor: exactly one row per posting, keyed by transfer id. Inserting it inside the
 * posting transaction makes a concurrent double-post fail the primary-key constraint and roll back —
 * the read-then-write check in the handler is only a fast path; correctness rests on this constraint.
 */
@Entity
@Table(name = "posting")
class PostingJpaEntity {

    @Id
    @Column(name = "transfer_id")
    private String transferId;

    protected PostingJpaEntity() {}

    PostingJpaEntity(String transferId) {
        this.transferId = transferId;
    }
}
