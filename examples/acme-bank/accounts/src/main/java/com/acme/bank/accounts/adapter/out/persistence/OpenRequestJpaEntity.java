package com.acme.bank.accounts.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotency anchor for account opening: one row per client request id. Inserting it inside the
 * open transaction makes a concurrent or retried open fail the primary-key constraint and roll back —
 * the same request id never opens two accounts.
 */
@Entity
@Table(name = "open_request")
class OpenRequestJpaEntity {

    @Id
    @Column(name = "request_id")
    private String requestId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    protected OpenRequestJpaEntity() {}

    OpenRequestJpaEntity(String requestId, String accountId) {
        this.requestId = requestId;
        this.accountId = accountId;
    }

    String getAccountId() {
        return accountId;
    }
}
