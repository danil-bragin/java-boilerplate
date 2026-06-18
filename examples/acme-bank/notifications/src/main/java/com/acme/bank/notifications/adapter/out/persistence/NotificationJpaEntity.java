package com.acme.bank.notifications.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification")
class NotificationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private String transferId;

    @Column(name = "channel", nullable = false)
    private String channel;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "status", nullable = false)
    private String status;

    protected NotificationJpaEntity() {}

    NotificationJpaEntity(String transferId, String channel, String message, String status) {
        this.transferId = transferId;
        this.channel = channel;
        this.message = message;
        this.status = status;
    }

    Long getId() {
        return id;
    }

    String getTransferId() {
        return transferId;
    }

    String getChannel() {
        return channel;
    }

    String getMessage() {
        return message;
    }

    String getStatus() {
        return status;
    }
}
