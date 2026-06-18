package com.acme.bank.notifications.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationJpaRepository extends JpaRepository<NotificationJpaEntity, Long> {
    long countByTransferId(String transferId);
}
