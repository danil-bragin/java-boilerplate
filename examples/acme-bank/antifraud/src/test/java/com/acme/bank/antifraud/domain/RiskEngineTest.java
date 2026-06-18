package com.acme.bank.antifraud.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

    // standard limit 10,000 USD; strict limit 1,000 USD
    private final RiskEngine standard = new RiskEngine(new RiskRules(Money.of("10000", Assets.USD), 5));
    private final RiskEngine strict = new RiskEngine(new RiskRules(Money.of("1000", Assets.USD), 5));

    @Test
    void approvesAmountUnderLimitAndUnderVelocity() {
        RiskDecision d = standard.assess(Money.of("500.00", Assets.USD), 0);
        assertThat(d.approved()).isTrue();
    }

    @Test
    void rejectsAmountOverLimit() {
        RiskDecision d = standard.assess(Money.of("25000.00", Assets.USD), 0);
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).isEqualTo("AMOUNT_LIMIT");
    }

    @Test
    void rejectsOverVelocity() {
        RiskDecision d = standard.assess(Money.of("100.00", Assets.USD), 5);
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).isEqualTo("VELOCITY");
    }

    @Test
    void strictRulesRejectLowerAmounts() {
        assertThat(strict.assess(Money.of("2000.00", Assets.USD), 0).approved()).isFalse();
        assertThat(standard.assess(Money.of("2000.00", Assets.USD), 0).approved())
                .isTrue();
    }
}
