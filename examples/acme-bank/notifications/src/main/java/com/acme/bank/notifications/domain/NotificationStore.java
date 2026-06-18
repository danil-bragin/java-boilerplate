package com.acme.bank.notifications.domain;

/** Port for persisting notifications. */
public interface NotificationStore {
    void save(Notification notification);

    long countByTransferId(String transferId);
}
