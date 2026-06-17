package com.acme.bank.accounts.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryJpaEntity, Long> {

    boolean existsByTransferId(String transferId);

    @Query("select coalesce(sum(e.amount.amount), 0) from LedgerEntryJpaEntity e "
            + "where e.accountId = :accountId and e.amount.asset = :asset")
    BigDecimal sumAmount(@Param("accountId") String accountId, @Param("asset") String asset);

    List<LedgerEntryJpaEntity> findByTransferId(String transferId);
}
