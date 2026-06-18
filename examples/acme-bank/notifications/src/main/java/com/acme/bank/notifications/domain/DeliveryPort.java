package com.acme.bank.notifications.domain;

/** Out-port: delivers a notification via some channel (email, SMS, push, etc.). */
public interface DeliveryPort {
    void deliver(Notification notification);
}
