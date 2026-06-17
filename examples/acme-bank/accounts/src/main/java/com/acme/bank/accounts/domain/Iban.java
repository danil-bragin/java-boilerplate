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
}
