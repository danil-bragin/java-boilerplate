package com.acme.bank.antifraud.domain;

import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record RiskDecision(boolean approved, String reason) {
    public static RiskDecision approve() {
        return new RiskDecision(true, null);
    }

    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, reason);
    }
}
