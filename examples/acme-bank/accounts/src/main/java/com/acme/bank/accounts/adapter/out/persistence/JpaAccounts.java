package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.bank.accounts.domain.Account;
import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.AccountStatus;
import com.acme.bank.accounts.domain.Accounts;
import com.acme.bank.accounts.domain.Iban;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JpaAccounts implements Accounts {

    private final AccountJpaRepository repository;

    JpaAccounts(AccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return repository
                .findById(id.value())
                .map(e -> new Account(
                        new AccountId(e.getId()), new Iban(e.getIban()), AccountStatus.valueOf(e.getStatus())));
    }
}
