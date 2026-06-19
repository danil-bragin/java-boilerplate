package com.acme.bank.transfers.adapter.out.persistence;

import java.time.Instant;
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

    /**
     * Stuck transfers: a non-terminal {@code status} that has not been touched since {@code cutoff}.
     * Oldest-first so the longest-stuck are reconciled first. Used by the saga reconciler.
     */
    @Query("select t from TransferJpaEntity t where t.status in :statuses "
            + "and t.updatedAt < :cutoff order by t.updatedAt asc")
    List<TransferJpaEntity> findStuck(
            @Param("statuses") List<String> statuses, @Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * BANK-22 Fix 1: count a source account's RECENT transfers (created since {@code since}) in transfers'
     * OWN DB. Drives the per-source fast-path velocity guard so a high-velocity source is routed to the
     * async screened slow-path instead of bypassing antifraud velocity. Backed by
     * {@code idx_transfer_source_created (source_account_id, created_at)}.
     */
    @Query("select count(t) from TransferJpaEntity t "
            + "where t.sourceAccountId = :sourceAccountId and t.createdAt > :since")
    long countBySourceSince(@Param("sourceAccountId") String sourceAccountId, @Param("since") Instant since);
}
