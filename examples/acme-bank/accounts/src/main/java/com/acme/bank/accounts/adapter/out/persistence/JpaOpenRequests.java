package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.OpenRequests;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JpaOpenRequests implements OpenRequests {

    private final OpenRequestJpaRepository repository;

    JpaOpenRequests(OpenRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AccountId> findAccountId(String requestId) {
        return repository.findById(requestId).map(e -> new AccountId(e.getAccountId()));
    }

    @Override
    public void record(String requestId, AccountId accountId) {
        // Flush so a concurrent or retried open with the same request id fails the PK constraint here.
        repository.saveAndFlush(new OpenRequestJpaEntity(requestId, accountId.value()));
    }
}
