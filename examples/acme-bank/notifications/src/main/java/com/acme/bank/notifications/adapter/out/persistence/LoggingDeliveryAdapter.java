package com.acme.bank.notifications.adapter.out.persistence;

import com.acme.bank.notifications.domain.DeliveryPort;
import com.acme.bank.notifications.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mock delivery adapter — logs the notification instead of sending a real message. */
@Component
class LoggingDeliveryAdapter implements DeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingDeliveryAdapter.class);

    @Override
    public void deliver(Notification notification) {
        log.info(
                "[MOCK-DELIVERY] channel={} transferId={} message={}",
                notification.channel(),
                notification.transferId(),
                notification.message());
    }
}
