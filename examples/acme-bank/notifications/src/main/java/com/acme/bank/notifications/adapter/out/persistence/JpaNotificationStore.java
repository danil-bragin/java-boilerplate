package com.acme.bank.notifications.adapter.out.persistence;

import com.acme.bank.notifications.domain.Notification;
import com.acme.bank.notifications.domain.NotificationStore;
import org.springframework.stereotype.Component;

@Component
class JpaNotificationStore implements NotificationStore {

    private final NotificationJpaRepository repository;

    JpaNotificationStore(NotificationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Notification notification) {
        repository.save(new NotificationJpaEntity(
                notification.transferId(), notification.channel(), notification.message(), notification.status()));
    }

    @Override
    public long countByTransferId(String transferId) {
        return repository.countByTransferId(transferId);
    }
}
