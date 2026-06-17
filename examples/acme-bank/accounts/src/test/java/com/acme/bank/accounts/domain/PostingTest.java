package com.acme.bank.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.money.Assets;
import com.acme.money.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostingTest {

    private final AccountId source = new AccountId("acc-src");
    private final AccountId dest = new AccountId("acc-dst");

    @Test
    void transferBuildsTwoBalancedEntries() {
        Posting posting = Posting.transfer("t-1", source, dest, Money.of("100.00", Assets.USD));
        assertThat(posting.entries()).hasSize(2);
        // source debited (negative), destination credited (positive), sum zero
        assertThat(posting.entries().get(0).amount()).isEqualTo(Money.of("-100.00", Assets.USD));
        assertThat(posting.entries().get(1).amount()).isEqualTo(Money.of("100.00", Assets.USD));
        assertThat(posting.transferId()).isEqualTo("t-1");
    }

    @Test
    void postingRejectsUnbalancedEntries() {
        List<LedgerEntry> unbalanced = List.of(
                new LedgerEntry(source, Money.of("-100.00", Assets.USD)),
                new LedgerEntry(dest, Money.of("99.00", Assets.USD)));
        assertThatThrownBy(() -> new Posting("t-2", unbalanced))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance");
    }

    @Test
    void postingRejectsFewerThanTwoEntries() {
        assertThatThrownBy(() -> new Posting("t-3", List.of(new LedgerEntry(source, Money.zero(Assets.USD)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
