package com.acme.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Immutable monetary amount: an arbitrary-precision decimal bound to an {@link Asset}. There is no
 * floating-point construction path. Arithmetic is exact; rounding is always explicit (see divide /
 * allocate). Equality is value-based and scale-insensitive (10.0 USD equals 10.00 USD).
 */
public final class Money implements Comparable<Money> {

    private final BigDecimal amount;
    private final Asset asset;

    private Money(BigDecimal amount, Asset asset) {
        this.amount = amount;
        this.asset = Objects.requireNonNull(asset, "asset");
    }

    /** Parse an exact decimal from a string (no float). */
    public static Money of(String amount, Asset asset) {
        Objects.requireNonNull(amount, "amount");
        return new Money(parseBounded(amount), asset);
    }

    /** Build from an integer count of the asset's smallest units (e.g. cents, wei). */
    public static Money ofMinor(BigInteger minor, Asset asset) {
        Objects.requireNonNull(minor, "minor");
        Objects.requireNonNull(asset, "asset");
        return new Money(new BigDecimal(minor).movePointLeft(asset.scale()), asset);
    }

    /** Build from a whole number of major units. */
    public static Money ofMajor(long major, Asset asset) {
        return new Money(BigDecimal.valueOf(major), asset);
    }

    public static Money zero(Asset asset) {
        return new Money(BigDecimal.ZERO, asset);
    }

    public Asset asset() {
        return asset;
    }

    public int signum() {
        return amount.signum();
    }

    public boolean isZero() {
        return signum() == 0;
    }

    public boolean isPositive() {
        return signum() > 0;
    }

    public boolean isNegative() {
        return signum() < 0;
    }

    /** Exact decimal string with no trailing zeros and no exponent (e.g. "10.05"). */
    public String toAmountString() {
        return amount.stripTrailingZeros().toPlainString();
    }

    public Money add(Money other) {
        requireSameAsset(other);
        return new Money(amount.add(other.amount), asset);
    }

    public Money subtract(Money other) {
        requireSameAsset(other);
        return new Money(amount.subtract(other.amount), asset);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), asset);
    }

    public Money negate() {
        return new Money(amount.negate(), asset);
    }

    public Money abs() {
        return new Money(amount.abs(), asset);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return asset.equals(other.asset) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(asset, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return toAmountString() + " " + asset.code();
    }

    // --- internal helpers (later tasks add arithmetic/allocation/format) ---

    static BigDecimal parseBounded(String text) {
        BigDecimal value = new BigDecimal(text);
        if (value.precision() > 1000) {
            throw new IllegalArgumentException("too many significant digits");
        }
        if (Math.abs((long) value.scale()) > 256) {
            throw new IllegalArgumentException("exponent out of bounds");
        }
        return value;
    }

    @Override
    public int compareTo(Money other) {
        requireSameAsset(other);
        return amount.compareTo(other.amount);
    }

    BigDecimal rawAmount() {
        return amount;
    }

    private void requireSameAsset(Money other) {
        if (!asset.equals(other.asset)) {
            throw new CurrencyMismatchException(asset, other.asset);
        }
    }
}
