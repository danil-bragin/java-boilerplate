package com.acme.bank.antifraud.domain;

import com.acme.money.Money;

/**
 * Deterministic risk assessment: amount-limit then velocity. Pure domain — a real ML engine would
 * implement the same conceptual contract behind this type.
 */
public class RiskEngine {

    private final RiskRules rules;

    public RiskEngine(RiskRules rules) {
        this.rules = rules;
    }

    /** Assess a transfer given its amount and the source's prior approved-transfer count. */
    public RiskDecision assess(Money amount, int sourceVelocity) {
        if (amount.compareTo(rules.maxAmount()) > 0) {
            return RiskDecision.reject("AMOUNT_LIMIT");
        }
        if (sourceVelocity >= rules.maxVelocity()) {
            return RiskDecision.reject("VELOCITY");
        }
        return RiskDecision.approve();
    }
}
