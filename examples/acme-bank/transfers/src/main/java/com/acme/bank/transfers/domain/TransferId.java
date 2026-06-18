package com.acme.bank.transfers.domain;

import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record TransferId(String value) {
    public TransferId {
        Objects.requireNonNull(value, "transfer id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("transfer id must not be blank");
        }
    }
}
