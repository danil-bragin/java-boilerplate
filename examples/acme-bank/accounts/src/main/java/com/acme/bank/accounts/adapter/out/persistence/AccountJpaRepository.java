package com.acme.bank.accounts.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, String> {}
