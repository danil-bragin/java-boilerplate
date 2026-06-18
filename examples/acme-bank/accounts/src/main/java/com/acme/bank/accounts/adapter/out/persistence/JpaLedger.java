package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.LedgerEntry;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Asset;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.persistence.MoneyAmount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
class JpaLedger implements Ledger {

    private final LedgerEntryJpaRepository entries;
    private final PostingJpaRepository postings;

    JpaLedger(LedgerEntryJpaRepository entries, PostingJpaRepository postings) {
        this.entries = entries;
        this.postings = postings;
    }

    @Override
    public void save(Posting posting) {
        // Insert the idempotency anchor first and flush so a concurrent duplicate fails the PK here
        // (throwing DataIntegrityViolationException) and rolls back before any entries are written.
        postings.saveAndFlush(new PostingJpaEntity(posting.transferId()));
        Instant postedAt = Instant.now();
        for (LedgerEntry entry : posting.entries()) {
            entries.save(new LedgerEntryJpaEntity(
                    posting.transferId(), entry.accountId().value(), MoneyAmount.from(entry.amount()), postedAt));
        }
    }

    @Override
    public boolean existsByTransferId(String transferId) {
        return entries.existsByTransferId(transferId);
    }

    @Override
    public Money balance(AccountId accountId, Asset asset) {
        BigDecimal sum = entries.sumAmount(accountId.value(), asset.code());
        return Money.of(sum.toPlainString(), asset);
    }

    @Override
    public Money balanceOf(AccountId accountId) {
        Asset asset = assetOf(accountId);
        return balance(accountId, asset);
    }

    @Override
    public Money balanceBefore(AccountId accountId, Instant at) {
        Asset asset = assetOf(accountId);
        BigDecimal sum = entries.sumAmountBefore(accountId.value(), asset.code(), at);
        return Money.of(sum.toPlainString(), asset);
    }

    @Override
    public List<PostedEntry> entriesFor(AccountId accountId, Instant from, Instant to, int page, int size) {
        List<LedgerEntryJpaEntity> rows = entries.findPage(accountId.value(), from, to, PageRequest.of(page, size));
        List<String> transferIds = rows.stream()
                .map(LedgerEntryJpaEntity::getTransferId)
                .distinct()
                .toList();
        Map<String, String> counterparties = new HashMap<>();
        if (!transferIds.isEmpty()) {
            for (LedgerEntryJpaEntity sibling : entries.findSiblings(transferIds, accountId.value())) {
                counterparties.putIfAbsent(sibling.getTransferId(), sibling.getAccountId());
            }
        }
        List<PostedEntry> result = new ArrayList<>(rows.size());
        for (LedgerEntryJpaEntity row : rows) {
            result.add(new PostedEntry(
                    row.getPostedAt(),
                    row.getTransferId(),
                    counterparties.get(row.getTransferId()),
                    row.getAmount().toMoney(Assets::of)));
        }
        return result;
    }

    private Asset assetOf(AccountId accountId) {
        String code = entries.assetOf(accountId.value());
        return code == null ? Assets.USD : Assets.of(code);
    }
}
