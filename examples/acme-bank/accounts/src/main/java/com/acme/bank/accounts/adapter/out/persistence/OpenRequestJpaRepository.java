package com.acme.bank.accounts.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface OpenRequestJpaRepository extends JpaRepository<OpenRequestJpaEntity, String> {}
