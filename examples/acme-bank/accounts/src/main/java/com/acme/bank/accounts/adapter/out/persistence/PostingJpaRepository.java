package com.acme.bank.accounts.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface PostingJpaRepository extends JpaRepository<PostingJpaEntity, String> {}
