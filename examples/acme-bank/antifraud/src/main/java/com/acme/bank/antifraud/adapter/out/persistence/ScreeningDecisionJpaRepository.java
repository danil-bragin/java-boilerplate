package com.acme.bank.antifraud.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ScreeningDecisionJpaRepository extends JpaRepository<ScreeningDecisionJpaEntity, String> {

    long countBySourceAccountIdAndApprovedTrue(String sourceAccountId);

    boolean existsByTransferId(String transferId);
}
