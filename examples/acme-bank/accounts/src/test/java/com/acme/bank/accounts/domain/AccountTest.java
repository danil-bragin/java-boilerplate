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

    private Account open() {
        return new Account(new AccountId("acc-1"), new Iban("DE89370400440532013000"));
    }

    @Test
    void freezeMovesOpenToFrozen() {
        Account account = open();
        account.freeze();
        assertThat(account.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(account.isOperational()).isFalse();
    }

    @Test
    void closeMovesOpenToClosed() {
        Account account = open();
        account.close();
        assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.isOperational()).isFalse();
    }

    @Test
    void closeMovesFrozenToClosed() {
        Account account = open();
        account.freeze();
        account.close();
        assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void freezeOnClosedThrows() {
        Account account = open();
        account.close();
        assertThatThrownBy(account::freeze).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closedAccountIsNotOperational() {
        Account account = new Account(new AccountId("acc-1"), new Iban("DE89370400440532013000"), AccountStatus.CLOSED);
        assertThat(account.isOperational()).isFalse();
    }

    @Test
    void frozenAccountIsNotOperational() {
        Account account = new Account(new AccountId("acc-1"), new Iban("DE89370400440532013000"), AccountStatus.FROZEN);
        assertThat(account.isOperational()).isFalse();
    }

    @Test
    void reconstructionPreservesStatus() {
        Account account = new Account(new AccountId("acc-1"), new Iban("DE89370400440532013000"), AccountStatus.FROZEN);
        assertThat(account.status()).isEqualTo(AccountStatus.FROZEN);
    }
}
