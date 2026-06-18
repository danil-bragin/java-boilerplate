package com.acme.bank.transfers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class TransferTest {

    private Transfer requested() {
        return Transfer.request(new TransferId("t-1"), "acc-src", "acc-dst", Money.of("100.00", Assets.USD), "alice");
    }

    @Test
    void requestStartsInRequested() {
        assertThat(requested().status()).isEqualTo(TransferStatus.REQUESTED);
    }

    @Test
    void approveThenPostThenComplete() {
        Transfer t = requested();
        t.approve();
        assertThat(t.status()).isEqualTo(TransferStatus.APPROVED);
        t.markPosting();
        assertThat(t.status()).isEqualTo(TransferStatus.POSTING);
        t.complete();
        assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void rejectFromRequestedFails() {
        Transfer t = requested();
        t.reject("FRAUD");
        assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void illegalTransitionThrows() {
        Transfer t = requested();
        assertThatThrownBy(t::complete).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rehydrateCompletedReconstructsCorrectStatus() {
        Transfer t = Transfer.rehydrate(
                new TransferId("t-rehydrate"),
                "acc-src",
                "acc-dst",
                Money.of("100.00", Assets.USD),
                "alice",
                TransferStatus.COMPLETED,
                null);
        assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.failureReason()).isNull();
    }

    @Test
    void rehydrateFailedReconstructsStatusAndReason() {
        Transfer t = Transfer.rehydrate(
                new TransferId("t-rehydrate-failed"),
                "acc-src",
                "acc-dst",
                Money.of("100.00", Assets.USD),
                "alice",
                TransferStatus.FAILED,
                "INSUFFICIENT_FUNDS");
        assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void rehydratePostingAllowsComplete() {
        Transfer t = Transfer.rehydrate(
                new TransferId("t-rehydrate-posting"),
                "acc-src",
                "acc-dst",
                Money.of("100.00", Assets.USD),
                "alice",
                TransferStatus.POSTING,
                null);
        t.complete();
        assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
    }
}
