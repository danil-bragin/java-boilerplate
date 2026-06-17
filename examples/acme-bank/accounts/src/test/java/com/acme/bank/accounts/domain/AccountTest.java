package com.acme.bank.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    void ibanRejectsBlank() {
        assertThatThrownBy(() -> new Iban("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountIdIsAValue() {
        assertThat(new AccountId("acc-1")).isEqualTo(new AccountId("acc-1"));
    }

    @Test
    void openAccountIsActive() {
        Account account = new Account(new AccountId("acc-1"), new Iban("DE89370400440532013000"));
        assertThat(account.status()).isEqualTo(AccountStatus.OPEN);
        assertThat(account.isOperational()).isTrue();
    }
}
