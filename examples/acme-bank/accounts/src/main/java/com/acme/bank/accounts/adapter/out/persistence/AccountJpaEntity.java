package com.acme.bank.accounts.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
class AccountJpaEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "iban", nullable = false)
    private String iban;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "asset", nullable = false)
    private String asset;

    protected AccountJpaEntity() {}

    AccountJpaEntity(String id, String iban, String status, String asset) {
        this.id = id;
        this.iban = iban;
        this.status = status;
        this.asset = asset;
    }

    String getId() {
        return id;
    }

    String getIban() {
        return iban;
    }

    String getStatus() {
        return status;
    }

    String getAsset() {
        return asset;
    }
}
