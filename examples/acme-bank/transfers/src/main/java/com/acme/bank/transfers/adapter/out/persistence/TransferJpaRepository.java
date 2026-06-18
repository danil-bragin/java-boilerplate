package com.acme.bank.transfers.adapter.out.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TransferJpaRepository extends JpaRepository<TransferJpaEntity, String> {

    /**
     * Paged query with optional filters. A null {@code accountId} or {@code status} disables that
     * filter; {@code accountId} matches either the source or destination side. Newest-first by id.
     */
    @Query("select t from TransferJpaEntity t where "
            + "(:accountId is null or t.sourceAccountId = :accountId or t.destinationAccountId = :accountId) "
            + "and (:status is null or t.status = :status) "
            + "order by t.id desc")
    List<TransferJpaEntity> query(
            @Param("accountId") String accountId, @Param("status") String status, Pageable pageable);
}
