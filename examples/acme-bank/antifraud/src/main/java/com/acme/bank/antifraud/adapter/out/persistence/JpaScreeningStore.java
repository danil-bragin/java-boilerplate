package com.acme.bank.antifraud.adapter.out.persistence;

import com.acme.bank.antifraud.application.ScreeningStore;
import org.springframework.stereotype.Component;

@Component
class JpaScreeningStore implements ScreeningStore {

    private final ScreeningDecisionJpaRepository repository;

    JpaScreeningStore(ScreeningDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(String transferId, String sourceAccountId, boolean approved, String reason) {
        repository.save(new ScreeningDecisionJpaEntity(transferId, sourceAccountId, approved, reason));
    }

    @Override
    public boolean existsByTransferId(String transferId) {
        return repository.existsByTransferId(transferId);
    }

    @Override
    public int velocity(String sourceAccountId) {
        return (int) repository.countBySourceAccountIdAndApprovedTrue(sourceAccountId);
    }
}
