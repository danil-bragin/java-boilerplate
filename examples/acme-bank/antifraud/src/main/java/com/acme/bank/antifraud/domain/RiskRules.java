package com.acme.bank.antifraud.domain;

import com.acme.money.Money;
import org.jmolecules.ddd.annotation.ValueObject;

/** Tunable risk thresholds: max single-transfer amount and max prior transfers per source (velocity). */
@ValueObject
public record RiskRules(Money maxAmount, int maxVelocity) {}
