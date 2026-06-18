package com.acme.bank.accounts.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryJpaEntity, Long> {

    boolean existsByTransferId(String transferId);

    @Query("select coalesce(sum(e.amount.amount), 0) from LedgerEntryJpaEntity e "
            + "where e.accountId = :accountId and e.amount.asset = :asset")
    BigDecimal sumAmount(@Param("accountId") String accountId, @Param("asset") String asset);

    List<LedgerEntryJpaEntity> findByTransferId(String transferId);

    /**
     * Sum of an account's entries ordered strictly before {@code (at, id)} in the SAME total order the
     * page uses {@code (postedAt asc, id asc)}. Passing the first row of a page as the {@code (at, id)}
     * boundary makes the seed exclude exactly the rows shown on prior pages — even when many entries
     * collide on a single {@code posted_at} — so the running balance stays correct across page borders.
     */
    @Query("select coalesce(sum(e.amount.amount), 0) from LedgerEntryJpaEntity e "
            + "where e.accountId = :accountId and e.amount.asset = :asset "
            + "and (e.postedAt < :at or (e.postedAt = :at and e.id < :id))")
    BigDecimal sumAmountBefore(
            @Param("accountId") String accountId,
            @Param("asset") String asset,
            @Param("at") Instant at,
            @Param("id") long id);

    /** A page of an account's entries within [from, to), oldest first. */
    @Query("select e from LedgerEntryJpaEntity e "
            + "where e.accountId = :accountId and e.postedAt >= :from and e.postedAt < :to "
            + "order by e.postedAt asc, e.id asc")
    List<LedgerEntryJpaEntity> findPage(
            @Param("accountId") String accountId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** Sibling entries of the given postings, used to resolve each line's counterparty. */
    @Query("select e from LedgerEntryJpaEntity e " + "where e.transferId in :transferIds and e.accountId <> :accountId")
    List<LedgerEntryJpaEntity> findSiblings(
            @Param("transferIds") List<String> transferIds, @Param("accountId") String accountId);
}
