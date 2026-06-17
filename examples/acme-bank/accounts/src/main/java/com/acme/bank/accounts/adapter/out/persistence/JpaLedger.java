package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.LedgerEntry;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Asset;
import com.acme.money.Money;
import com.acme.persistence.MoneyAmount;
import org.springframework.stereotype.Component;

@Component
class JpaLedger implements Ledger {

    private final LedgerEntryJpaRepository entries;

    JpaLedger(LedgerEntryJpaRepository entries) {
        this.entries = entries;
    }

    @Override
    public void save(Posting posting) {
        for (LedgerEntry entry : posting.entries()) {
            entries.save(new LedgerEntryJpaEntity(
                    posting.transferId(), entry.accountId().value(), MoneyAmount.from(entry.amount())));
        }
    }

    @Override
    public boolean existsByTransferId(String transferId) {
        return entries.existsByTransferId(transferId);
    }

    @Override
    public Money balance(AccountId accountId, Asset asset) {
        java.math.BigDecimal sum = entries.sumAmount(accountId.value(), asset.code());
        return Money.of(sum.toPlainString(), asset);
    }
}
