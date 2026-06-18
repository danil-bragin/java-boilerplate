package com.acme.bank.notifications.domain;

import org.jmolecules.ddd.annotation.AggregateRoot;

/** A notification generated for a terminal transfer event. */
@AggregateRoot
public class Notification {

    private final String transferId;
    private final String channel;
    private final String message;
    private final String status;

    public Notification(String transferId, String channel, String message, String status) {
        this.transferId = transferId;
        this.channel = channel;
        this.message = message;
        this.status = status;
    }

    public String transferId() {
        return transferId;
    }

    public String channel() {
        return channel;
    }

    public String message() {
        return message;
    }

    public String status() {
        return status;
    }
}
