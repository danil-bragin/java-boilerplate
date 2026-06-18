package com.acme.bank.notifications.application;

import com.acme.bank.notifications.domain.DeliveryPort;
import com.acme.bank.notifications.domain.Notification;
import com.acme.bank.notifications.domain.NotificationStore;
import org.springframework.stereotype.Service;

/** Application service: builds a notification, persists it, and delegates to the delivery port. */
@Service
public class NotifyOnTransfer {

    private final NotificationStore store;
    private final DeliveryPort delivery;

    public NotifyOnTransfer(NotificationStore store, DeliveryPort delivery) {
        this.store = store;
        this.delivery = delivery;
    }

    public void notifyCompleted(String transferId) {
        Notification notification =
                new Notification(transferId, "EMAIL", "Transfer " + transferId + " completed.", "DELIVERED");
        store.save(notification);
        delivery.deliver(notification);
    }

    public void notifyFailed(String transferId, String reason) {
        Notification notification = new Notification(
                transferId, "EMAIL", "Transfer " + transferId + " failed: " + reason + ".", "DELIVERED");
        store.save(notification);
        delivery.deliver(notification);
    }
}
