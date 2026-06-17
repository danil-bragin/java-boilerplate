package com.acme.bank.accounts.domain;

import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record AccountId(String value) {
    public AccountId {
        Objects.requireNonNull(value, "account id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("account id must not be blank");
        }
    }
}
