package com.acme.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /** Divide with an explicit result scale and rounding mode (guard against silent precision loss). */
    public Money divide(BigDecimal divisor, int scale, RoundingMode mode) {
        return new Money(amount.divide(divisor, scale, mode), asset);
    }

    /** Divide with banker's rounding ({@link RoundingMode#HALF_EVEN}) — the money default. */
    public Money divide(BigDecimal divisor, int scale) {
        return divide(divisor, scale, RoundingMode.HALF_EVEN);
    }

    /**
     * Allocate this amount across the given integer ratios, conserving every minor unit (Fowler
     * allocation, sign-aware). Allocation IS the rounding boundary: the amount is first taken at the
     * asset's minor-unit scale (banker's rounding if it carries sub-minor precision, e.g. a value
     * produced by {@link #multiply}), so the allocated parts sum to that rounded total — round once,
     * here. The leftover minor unit(s) are distributed one at a time across positive-ratio buckets
     * only; a ratio of {@code 0} always receives nothing.
     */
    public List<Money> allocate(int... ratios) {
        if (ratios.length == 0) {
            throw new IllegalArgumentException("at least one ratio is required");
        }
        long ratioSum = 0;
        for (int ratio : ratios) {
            if (ratio < 0) {
                throw new IllegalArgumentException("ratios must be >= 0");
            }
            ratioSum += ratio;
        }
        if (ratioSum == 0) {
            throw new IllegalArgumentException("ratio sum must be > 0");
        }

        int scale = asset.scale();
        BigInteger totalMinor = amount.setScale(scale, RoundingMode.HALF_EVEN)
                .movePointRight(scale)
                .toBigIntegerExact();
        BigInteger sum = BigInteger.valueOf(ratioSum);

        BigInteger[] shares = new BigInteger[ratios.length];
        BigInteger remainder = totalMinor;
        for (int i = 0; i < ratios.length; i++) {
            // truncate toward zero
            BigInteger share =
                    totalMinor.multiply(BigInteger.valueOf(ratios[i])).divide(sum);
            shares[i] = share;
            remainder = remainder.subtract(share);
        }
        // distribute the remainder one minor unit at a time (sign-aware), positive-ratio buckets only
        int step = remainder.signum() >= 0 ? 1 : -1;
        BigInteger stepValue = BigInteger.valueOf(step);
        int i = 0;
        while (remainder.signum() != 0) {
            if (ratios[i] > 0) {
                shares[i] = shares[i].add(stepValue);
                remainder = remainder.subtract(stepValue);
            }
            i = (i + 1) % ratios.length;
        }

        List<Money> result = new ArrayList<>(ratios.length);
        for (BigInteger share : shares) {
            result.add(new Money(new BigDecimal(share).movePointLeft(scale), asset));
        }
        return result;
    }

    /** Split into {@code n} as-equal-as-possible parts (conserves every unit). */
    public List<Money> split(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0");
        }
        int[] ratios = new int[n];
        Arrays.fill(ratios, 1);
        return allocate(ratios);
    }

    /** Human/display string at the asset's scale (banker's rounding), e.g. "10.00 USD". */
    public String format() {
        return amount.setScale(asset.scale(), RoundingMode.HALF_EVEN).toPlainString() + " " + asset.code();
    }

    /** This amount as an integer count of the asset's smallest units (banker's rounding). */
    public BigInteger toMinor() {
        return amount.setScale(asset.scale(), RoundingMode.HALF_EVEN)
                .movePointRight(asset.scale())
                .toBigIntegerExact();
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
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
