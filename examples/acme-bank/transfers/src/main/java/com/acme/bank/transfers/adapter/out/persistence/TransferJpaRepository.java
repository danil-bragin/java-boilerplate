package com.acme.bank.transfers.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface TransferJpaRepository extends JpaRepository<TransferJpaEntity, String> {}
