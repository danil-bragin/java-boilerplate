package com.acme.bank.accounts.domain;

import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record Iban(String value) {
    public Iban {
        Objects.requireNonNull(value, "iban");
        if (value.isBlank()) {
            throw new IllegalArgumentException("iban must not be blank");
        }
    }

    /**
     * Generate a deterministic, valid-shaped IBAN from an account id: {@code ACME} + an 18-digit
     * zero-padded number derived from the id's hash. Deliberately simple — NOT a real MOD-97 IBAN;
     * it is the example's stable account number, not a routable one.
     */
    public static Iban forAccount(AccountId accountId) {
        long digits = Math.abs((long) accountId.value().hashCode()) % 1_000_000_000_000_000_000L;
        return new Iban("ACME%018d".formatted(digits));
    }
}
