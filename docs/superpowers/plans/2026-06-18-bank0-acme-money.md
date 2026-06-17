# BANK-0: `acme-money` value-type library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** A dependency-light, immutable `Money` value type (BigDecimal + asset registry) mirroring the Go `platform/money` — exact arithmetic, explicit banker's rounding, Fowler allocation, same-asset invariant, string serialization, DoS-bounded parsing — exhaustively unit- and property-tested. No Spring, no DB (JPA `NUMERIC+asset` round-trip happens in BANK-1 where `Money` is mapped on an entity).

**Architecture:** A pure Java library module `starters/acme-money` (applies `acme.java-conventions`, no Spring). `Money` stores a private `BigDecimal` (the arbitrary-precision decimal, shopspring/decimal equivalent) + an `Asset` (code + minor-unit scale) from a code registry. Construction is string/integer only — no float path. Add/subtract/multiply are exact (scale grows); division is explicit with a `RoundingMode` (default `HALF_EVEN`); `allocate`/`split` conserve every minor unit (Fowler). Equality is value-based (scale-insensitive). The wire form is a `{amount:string, asset:string}` string pair.

**Tech Stack:** Java 21, JUnit 5 + AssertJ, jqwik (property testing), `java.math.BigDecimal`. No Spring, no Confluent, no DB.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §3 (acme-money). Mirrors go-boilerplate ADR-0020.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Maven Central fast. `gradle :starters:acme-money:spotlessApply` before each commit. Spotless `removeUnusedImports` is active.

---

## File structure

```
gradle/libs.versions.toml                          MODIFY: jqwik alias
settings.gradle.kts                                MODIFY: include acme-money
starters/acme-money/
  build.gradle.kts                                 NEW
  src/main/java/com/acme/money/
    Asset.java                                     NEW (record: code + scale)
    Assets.java                                    NEW (registry: fiat + crypto)
    Money.java                                     NEW (the value type — grows across tasks)
    CurrencyMismatchException.java                 NEW
  src/test/java/com/acme/money/
    AssetsTest.java                                NEW
    MoneyConstructionTest.java                     NEW
    MoneyArithmeticTest.java                       NEW
    MoneyDivisionTest.java                         NEW
    MoneyAllocationTest.java                       NEW
    MoneyCompareEqualityTest.java                  NEW
    MoneyFormatSerdeTest.java                      NEW
    MoneyValidationTest.java                       NEW
    MoneyPropertyTest.java                         NEW (jqwik)
```

---

## Task 1: Module scaffold + catalog

**Files:** `gradle/libs.versions.toml` (modify), `settings.gradle.kts` (modify), `starters/acme-money/build.gradle.kts` (create).

- [ ] **Step 1:** In `gradle/libs.versions.toml` add to `[versions]`: `jqwik = "1.9.2"` and to `[libraries]`:
```toml
jqwik = { module = "net.jqwik:jqwik", version.ref = "jqwik" }
```
- [ ] **Step 2:** In `settings.gradle.kts`, add `"starters:acme-money",` to the `include(...)` list (after `platform:acme-bom` or anywhere in the list).
- [ ] **Step 3:** `mkdir -p starters/acme-money/src/main/java/com/acme/money starters/acme-money/src/test/java/com/acme/money`
- [ ] **Step 4:** Create `starters/acme-money/build.gradle.kts`:
```kotlin
plugins {
    id("acme.java-conventions")
}

dependencies {
    testImplementation(platform(project(":platform:acme-bom")))
    testImplementation(libs.spring.boot.starter.test) // brings JUnit5 + AssertJ
    testImplementation(libs.jqwik)
}
```
- [ ] **Step 5:** Verify: `gradle :starters:acme-money:help -q` → BUILD SUCCESSFUL.
- [ ] **Step 6:** Commit:
```bash
git add gradle/libs.versions.toml settings.gradle.kts starters/acme-money/build.gradle.kts
git commit -m "build(acme-money): module scaffold + jqwik alias"
```

---

## Task 2: `Asset` + `Assets` registry (TDD)

**Files:** Create `Asset.java`, `Assets.java`, test `AssetsTest.java`.

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/AssetsTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AssetsTest {

    @Test
    void registeredAssetsHaveCorrectScale() {
        assertThat(Assets.USD.scale()).isEqualTo(2);
        assertThat(Assets.JPY.scale()).isZero();
        assertThat(Assets.BHD.scale()).isEqualTo(3);
        assertThat(Assets.ETH.scale()).isEqualTo(18);
        assertThat(Assets.USDC.scale()).isEqualTo(6);
    }

    @Test
    void lookupByCodeReturnsRegisteredAsset() {
        assertThat(Assets.of("USD")).isEqualTo(Assets.USD);
    }

    @Test
    void lookupOfUnknownAssetThrows() {
        assertThatThrownBy(() -> Assets.of("XYZ")).isInstanceOf(IllegalArgumentException.class);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-money:test --tests "*AssetsTest"` → FAIL (Asset/Assets missing).
- [ ] **Step 3: `Asset`** — `src/main/java/com/acme/money/Asset.java`:
```java
package com.acme.money;

/** An asset (currency or crypto): a stable code plus its minor-unit scale (decimal places). */
public record Asset(String code, int scale) {

    public Asset {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("asset code is required");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("asset scale must be >= 0");
        }
    }
}
```
- [ ] **Step 4: `Assets`** — `src/main/java/com/acme/money/Assets.java`:
```java
package com.acme.money;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-code registry of known assets (ISO-4217 fiat + crypto). No database lookup. */
public final class Assets {

    private static final ConcurrentMap<String, Asset> REGISTRY = new ConcurrentHashMap<>();

    public static final Asset USD = register(new Asset("USD", 2));
    public static final Asset EUR = register(new Asset("EUR", 2));
    public static final Asset JPY = register(new Asset("JPY", 0));
    public static final Asset BHD = register(new Asset("BHD", 3));
    public static final Asset ETH = register(new Asset("ETH", 18));
    public static final Asset USDC = register(new Asset("USDC", 6));

    private Assets() {}

    private static Asset register(Asset asset) {
        REGISTRY.put(asset.code(), asset);
        return asset;
    }

    /** Returns the registered asset for the code, or throws if unknown. */
    public static Asset of(String code) {
        Asset asset = REGISTRY.get(code);
        if (asset == null) {
            throw new IllegalArgumentException("unknown asset: " + code);
        }
        return asset;
    }

    public static Optional<Asset> find(String code) {
        return Optional.ofNullable(REGISTRY.get(code));
    }
}
```
- [ ] **Step 5: run, PASS** — `gradle :starters:acme-money:test --tests "*AssetsTest"` → PASS.
- [ ] **Step 6: format + commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "feat(acme-money): Asset value type + in-code registry"
```

---

## Task 3: `Money` construction — no float path (TDD)

**Files:** Create `Money.java`, `CurrencyMismatchException.java`, test `MoneyConstructionTest.java`.

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/MoneyConstructionTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class MoneyConstructionTest {

    @Test
    void ofStringParsesExactDecimal() {
        Money m = Money.of("10.05", Assets.USD);
        assertThat(m.asset()).isEqualTo(Assets.USD);
        assertThat(m.toAmountString()).isEqualTo("10.05");
    }

    @Test
    void ofMinorInterpretsSmallestUnits() {
        // 1005 cents = 10.05 USD
        Money m = Money.ofMinor(BigInteger.valueOf(1005), Assets.USD);
        assertThat(m.toAmountString()).isEqualTo("10.05");
        // 1 wei = 0.000000000000000001 ETH (18 dp)
        Money wei = Money.ofMinor(BigInteger.ONE, Assets.ETH);
        assertThat(wei.toAmountString()).isEqualTo("0.000000000000000001");
    }

    @Test
    void ofMajorTakesWholeUnits() {
        assertThat(Money.ofMajor(42, Assets.USD).toAmountString()).isEqualTo("42");
    }

    @Test
    void zeroIsZero() {
        assertThat(Money.zero(Assets.USD).isZero()).isTrue();
    }

    @Test
    void rejectsNullAsset() {
        assertThatThrownBy(() -> Money.of("1", null)).isInstanceOf(NullPointerException.class);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-money:test --tests "*MoneyConstructionTest"` → FAIL.
- [ ] **Step 3: `CurrencyMismatchException`** — `src/main/java/com/acme/money/CurrencyMismatchException.java`:
```java
package com.acme.money;

/** Thrown when a binary money operation is attempted across differing assets. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Asset left, Asset right) {
        super("currency mismatch: " + left.code() + " vs " + right.code());
    }
}
```
- [ ] **Step 4: `Money` (construction + accessors only)** — `src/main/java/com/acme/money/Money.java`:
```java
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
```
> `toAmountString()` of "42" yields "42"; of "10.05" yields "10.05"; of an 18-dp wei value yields the full decimal. `stripTrailingZeros().toPlainString()` is used so equality-by-value and the string form agree. (Note: `Money.of("1", null)` triggers the `Objects.requireNonNull(asset)` in the constructor — NPE — matching the test.)
- [ ] **Step 5: run, PASS** — `gradle :starters:acme-money:test --tests "*MoneyConstructionTest"` → PASS.
- [ ] **Step 6: format + commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "feat(acme-money): Money construction (string/minor/major), no float path"
```

---

## Task 4: Arithmetic — exact, same-asset invariant (TDD)

**Files:** Modify `Money.java` (add methods), test `MoneyArithmeticTest.java`.

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/MoneyArithmeticTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyArithmeticTest {

    @Test
    void addAndSubtractAreExact() {
        Money a = Money.of("10.05", Assets.USD);
        Money b = Money.of("0.95", Assets.USD);
        assertThat(a.add(b)).isEqualTo(Money.of("11.00", Assets.USD));
        assertThat(a.subtract(b)).isEqualTo(Money.of("9.10", Assets.USD));
    }

    @Test
    void multiplyGrowsScaleExactly() {
        Money price = Money.of("10.00", Assets.USD);
        Money taxed = price.multiply(new BigDecimal("1.015"));
        assertThat(taxed).isEqualTo(Money.of("10.15", Assets.USD)); // 10.00 * 1.015 = 10.150
    }

    @Test
    void negateAndAbs() {
        Money debit = Money.of("-5.00", Assets.USD);
        assertThat(debit.negate()).isEqualTo(Money.of("5.00", Assets.USD));
        assertThat(debit.abs()).isEqualTo(Money.of("5.00", Assets.USD));
    }

    @Test
    void mixingAssetsThrows() {
        assertThatThrownBy(() -> Money.of("1", Assets.USD).add(Money.of("1", Assets.EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-money:test --tests "*MoneyArithmeticTest"` → FAIL (methods missing).
- [ ] **Step 3: add methods to `Money`** (insert before the `// --- internal helpers` comment):
```java
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
```
(`requireSameAsset` is private; these methods live in the same class so they can call it. Add `import java.math.BigDecimal;` is already present.)
- [ ] **Step 4: run, PASS** — `gradle :starters:acme-money:test --tests "*MoneyArithmeticTest"` → PASS.
- [ ] **Step 5: format + commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "feat(acme-money): exact add/subtract/multiply/negate/abs + same-asset invariant"
```

---

## Task 5: Division — explicit banker's rounding (TDD)

**Files:** Modify `Money.java`, test `MoneyDivisionTest.java`.

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/MoneyDivisionTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyDivisionTest {

    @Test
    void divideRoundsHalfEvenByDefault() {
        // 10.00 / 3 = 3.333... -> 3.33 at scale 2
        Money r = Money.of("10.00", Assets.USD).divide(new BigDecimal("3"), 2);
        assertThat(r).isEqualTo(Money.of("3.33", Assets.USD));
    }

    @Test
    void bankersRoundingRoundsHalfToEven() {
        // 2.125 / 1 at scale 2 -> 2.12 (half to even), 2.135 -> 2.14
        assertThat(Money.of("2.125", Assets.USD).divide(BigDecimal.ONE, 2))
                .isEqualTo(Money.of("2.12", Assets.USD));
        assertThat(Money.of("2.135", Assets.USD).divide(BigDecimal.ONE, 2))
                .isEqualTo(Money.of("2.14", Assets.USD));
    }

    @Test
    void explicitRoundingModeIsHonoured() {
        assertThat(Money.of("10.00", Assets.USD).divide(new BigDecimal("3"), 2, RoundingMode.UP))
                .isEqualTo(Money.of("3.34", Assets.USD));
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-money:test --tests "*MoneyDivisionTest"` → FAIL.
- [ ] **Step 3: add to `Money`** (and `import java.math.RoundingMode;` at the top):
```java
    /** Divide with an explicit result scale and rounding mode (guard against silent precision loss). */
    public Money divide(BigDecimal divisor, int scale, RoundingMode mode) {
        return new Money(amount.divide(divisor, scale, mode), asset);
    }

    /** Divide with banker's rounding ({@link RoundingMode#HALF_EVEN}) — the money default. */
    public Money divide(BigDecimal divisor, int scale) {
        return divide(divisor, scale, RoundingMode.HALF_EVEN);
    }
```
- [ ] **Step 4: run, PASS** — `gradle :starters:acme-money:test --tests "*MoneyDivisionTest"` → PASS.
- [ ] **Step 5: format + commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "feat(acme-money): explicit division with banker's-rounding default"
```

---

## Task 6: Allocation — Fowler, conserves every unit (TDD)

**Files:** Modify `Money.java`, test `MoneyAllocationTest.java`.

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/MoneyAllocationTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyAllocationTest {

    @Test
    void allocateConservesEveryMinorUnit() {
        // 0.05 USD split 3 ways -> 0.02, 0.02, 0.01 (sum == 0.05, no penny lost)
        List<Money> parts = Money.of("0.05", Assets.USD).split(3);
        assertThat(parts).containsExactly(
                Money.of("0.02", Assets.USD),
                Money.of("0.02", Assets.USD),
                Money.of("0.01", Assets.USD));
        assertThat(parts.stream().reduce(Money.zero(Assets.USD), Money::add))
                .isEqualTo(Money.of("0.05", Assets.USD));
    }

    @Test
    void allocateByRatios() {
        // 1.00 USD allocated 1:1:2 -> 0.25, 0.25, 0.50
        List<Money> parts = Money.of("1.00", Assets.USD).allocate(1, 1, 2);
        assertThat(parts).containsExactly(
                Money.of("0.25", Assets.USD),
                Money.of("0.25", Assets.USD),
                Money.of("0.50", Assets.USD));
    }

    @Test
    void allocateIsSignAware() {
        List<Money> parts = Money.of("-0.05", Assets.USD).split(3);
        assertThat(parts.stream().reduce(Money.zero(Assets.USD), Money::add))
                .isEqualTo(Money.of("-0.05", Assets.USD));
    }

    @Test
    void allocateRejectsEmptyOrZeroRatios() {
        assertThatThrownBy(() -> Money.of("1.00", Assets.USD).allocate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.00", Assets.USD).allocate(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-money:test --tests "*MoneyAllocationTest"` → FAIL.
- [ ] **Step 3: add to `Money`** (add imports `java.math.BigInteger`, `java.util.ArrayList`, `java.util.Arrays`, `java.util.List`, `java.math.RoundingMode` is already there):
```java
    /**
     * Allocate this amount across the given integer ratios, conserving every minor unit (Fowler
     * allocation, sign-aware). The amount is first taken at the asset's minor-unit scale (banker's
     * rounding if it carries sub-minor precision); the remainder is distributed one unit at a time.
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
        BigInteger totalMinor =
                amount.setScale(scale, RoundingMode.HALF_EVEN).movePointRight(scale).toBigIntegerExact();
        BigInteger sum = BigInteger.valueOf(ratioSum);

        BigInteger[] shares = new BigInteger[ratios.length];
        BigInteger remainder = totalMinor;
        for (int i = 0; i < ratios.length; i++) {
            // truncate toward zero
            BigInteger share = totalMinor.multiply(BigInteger.valueOf(ratios[i])).divide(sum);
            shares[i] = share;
            remainder = remainder.subtract(share);
        }
        // distribute the remainder one minor unit at a time (sign-aware)
        int step = remainder.signum() >= 0 ? 1 : -1;
        BigInteger stepValue = BigInteger.valueOf(step);
        int i = 0;
        while (remainder.signum() != 0) {
            shares[i] = shares[i].add(stepValue);
            remainder = remainder.subtract(stepValue);
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
```
- [ ] **Step 4: run, PASS** — `gradle :starters:acme-money:test --tests "*MoneyAllocationTest"` → PASS.
- [ ] **Step 5: format + commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "feat(acme-money): Fowler allocate/split conserving every minor unit"
```

---

## Task 7: Compare, equality, min/max (TDD)

**Files:** Modify `Money.java` (add `equals`/`hashCode`/`min`/`max`), test `MoneyCompareEqualityTest.java`.

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/MoneyCompareEqualityTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyCompareEqualityTest {

    @Test
    void equalityIsScaleInsensitive() {
        assertThat(Money.of("10.0", Assets.USD)).isEqualTo(Money.of("10.00", Assets.USD));
        assertThat(Money.of("10.0", Assets.USD)).hasSameHashCodeAs(Money.of("10.00", Assets.USD));
    }

    @Test
    void differentAssetsAreNotEqual() {
        assertThat(Money.of("10", Assets.USD)).isNotEqualTo(Money.of("10", Assets.EUR));
    }

    @Test
    void comparison() {
        Money low = Money.of("1.00", Assets.USD);
        Money high = Money.of("2.00", Assets.USD);
        assertThat(low).isLessThan(high);
        assertThat(low.min(high)).isEqualTo(low);
        assertThat(low.max(high)).isEqualTo(high);
    }

    @Test
    void comparingDifferentAssetsThrows() {
        assertThatThrownBy(() -> Money.of("1", Assets.USD).compareTo(Money.of("1", Assets.EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-money:test --tests "*MoneyCompareEqualityTest"` → FAIL (equals not overridden → scale-sensitive default... actually default `equals` is identity; the test expects value equality → fails).
- [ ] **Step 3: add to `Money`** (the `compareTo` method already exists; add `min`/`max`/`equals`/`hashCode`/`toString` — `toString` will be refined in Task 8, add a temporary one now):
```java
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
```
- [ ] **Step 4: run, PASS** — `gradle :starters:acme-money:test --tests "*MoneyCompareEqualityTest"` → PASS. (Also re-run all prior money tests — `gradle :starters:acme-money:test` — they still pass with value equality.)
- [ ] **Step 5: format + commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "feat(acme-money): value equality (scale-insensitive), min/max, toString"
```

---

## Task 8: Format + string serde (TDD)

**Files:** Modify `Money.java` (add `format`, `toMinor`), test `MoneyFormatSerdeTest.java`.

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/MoneyFormatSerdeTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class MoneyFormatSerdeTest {

    @Test
    void formatRendersAtAssetScale() {
        assertThat(Money.of("10", Assets.USD).format()).isEqualTo("10.00 USD");
        assertThat(Money.of("10", Assets.JPY).format()).isEqualTo("10 JPY");
    }

    @Test
    void toMinorReturnsSmallestUnits() {
        assertThat(Money.of("10.05", Assets.USD).toMinor()).isEqualTo(BigInteger.valueOf(1005));
        assertThat(Money.ofMinor(BigInteger.ONE, Assets.ETH).toMinor()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void wireRoundTripViaStrings() {
        Money original = Money.of("1234.56", Assets.USD);
        // wire form: amount string + asset code
        String amount = original.toAmountString();
        String code = original.asset().code();
        Money parsed = Money.of(amount, Assets.of(code));
        assertThat(parsed).isEqualTo(original);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :starters:acme-money:test --tests "*MoneyFormatSerdeTest"` → FAIL (`format`/`toMinor` missing).
- [ ] **Step 3: add to `Money`:**
```java
    /** Human/display string at the asset's scale (banker's rounding), e.g. "10.00 USD". */
    public String format() {
        return amount.setScale(asset.scale(), RoundingMode.HALF_EVEN).toPlainString() + " " + asset.code();
    }

    /** This amount as an integer count of the asset's smallest units (banker's rounding). */
    public BigInteger toMinor() {
        return amount.setScale(asset.scale(), RoundingMode.HALF_EVEN).movePointRight(asset.scale()).toBigIntegerExact();
    }
```
- [ ] **Step 4: run, PASS** — `gradle :starters:acme-money:test --tests "*MoneyFormatSerdeTest"` → PASS.
- [ ] **Step 5: format + commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "feat(acme-money): format() at asset scale + toMinor() + string wire round-trip"
```

---

## Task 9: Validation / DoS bounds (TDD)

**Files:** Test `MoneyValidationTest.java` (the bounds were implemented in Task 3's `parseBounded`; this task proves them).

- [ ] **Step 1: failing test** — `src/test/java/com/acme/money/MoneyValidationTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyValidationTest {

    @Test
    void rejectsTooManySignificantDigits() {
        String huge = "1".repeat(1001);
        assertThatThrownBy(() -> Money.of(huge, Assets.USD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExponentOutOfBounds() {
        // scale > 256 (very long fractional part) is rejected
        String deepFraction = "0." + "0".repeat(257) + "1";
        assertThatThrownBy(() -> Money.of(deepFraction, Assets.USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumeric() {
        assertThatThrownBy(() -> Money.of("not-a-number", Assets.USD))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void acceptsRealisticCryptoPrecision() {
        // 18-dp ETH is well within bounds
        assertThat(Money.of("1.000000000000000001", Assets.ETH).toMinor()).isNotNull();
    }
}
```
- [ ] **Step 2: run** — `gradle :starters:acme-money:test --tests "*MoneyValidationTest"` → should PASS already (bounds live in `parseBounded` from Task 3). If any case fails, adjust `parseBounded` so: `precision() > 1000` rejects huge significant digits, `scale() > 256` rejects deep fractions, and a non-numeric string throws `NumberFormatException` (the `new BigDecimal(text)` does this before the bound checks — keep that order).
- [ ] **Step 3: commit** (tests only, or any `parseBounded` tweak):
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "test(acme-money): validation + DoS magnitude bounds"
```

---

## Task 10: Property tests (jqwik) — allocation conservation + parse round-trip

**Files:** Test `MoneyPropertyTest.java`.

- [ ] **Step 1: enable jqwik on the test task** — in `starters/acme-money/build.gradle.kts`, ensure JUnit Platform picks up jqwik. The `acme.java-conventions` plugin already sets `useJUnitPlatform()`; jqwik registers via the platform automatically. No change needed unless the test engine isn't found — if so, add to the build file:
```kotlin
tasks.named<Test>("test") {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
}
```
- [ ] **Step 2: property test** — `src/test/java/com/acme/money/MoneyPropertyTest.java`:
```java
package com.acme.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class MoneyPropertyTest {

    @Property
    void allocationAlwaysConservesTheTotal(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long cents,
            @ForAll @IntRange(min = 1, max = 20) int parts) {
        Money total = Money.ofMinor(java.math.BigInteger.valueOf(cents), Assets.USD);
        List<Money> allocated = total.split(parts);
        Money sum = allocated.stream().reduce(Money.zero(Assets.USD), Money::add);
        assertThat(sum).isEqualTo(total);
    }

    @Property
    void amountStringRoundTrips(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long cents) {
        Money money = Money.ofMinor(java.math.BigInteger.valueOf(cents), Assets.USD);
        Money parsed = Money.of(money.toAmountString(), Assets.USD);
        assertThat(parsed).isEqualTo(money);
    }
}
```
- [ ] **Step 3: run** — `gradle :starters:acme-money:test --tests "*MoneyPropertyTest"` → PASS (jqwik runs ~1000 randomized cases per property). If the engine isn't found, apply the build-file tweak from Step 1 and re-run.
- [ ] **Step 4: commit:**
```bash
gradle :starters:acme-money:spotlessApply
git add starters/acme-money
git commit -m "test(acme-money): jqwik property tests — allocation conservation + parse round-trip"
```

---

## Task 11: Full module build + ADR

**Files:** `docs/decisions/0011-money-representation.md`.

- [ ] **Step 1: run the whole module suite + full build:**
```
gradle :starters:acme-money:test
gradle build
```
Both → BUILD SUCCESSFUL (all `acme-money` tests + no regression to the rest).

- [ ] **Step 2: ADR** — `docs/decisions/0011-money-representation.md`:
```markdown
---
status: accepted
date: 2026-06-18
---

# Money representation (`acme-money`) — mirror of the Go platform/money

## Decision Outcome

- `Money` is an immutable value type: an arbitrary-precision `BigDecimal` (encapsulated) bound to an
  `Asset` (code + minor-unit scale) from an in-code registry (ISO-4217 fiat + crypto). One Money for
  fiat (0/2/3 dp), crypto (18 dp), and FX rates — no int64 overflow ceiling.
- No floating-point construction path (`of(String)`, `ofMinor(BigInteger)`, `ofMajor(long)` only).
- Exact `add`/`subtract`/`multiply` (scale grows); division is explicit with a `RoundingMode`
  (default `HALF_EVEN`, banker's); `allocate`/`split` use Fowler allocation and conserve every minor
  unit (sign-aware). Round once at the boundary.
- Same-asset invariant: cross-asset binary ops throw `CurrencyMismatchException`; no implicit coercion.
- Value equality is scale-insensitive (10.0 == 10.00).
- Wire form is a `{amount:string, asset:string}` string pair (a number would be float-parsed);
  storage is `NUMERIC + asset VARCHAR` (mapped where Money is used on an entity — BANK-1).
- Text parsing is magnitude-bounded (> 1000 significant digits or |scale| > 256 rejected) against DoS.
- Tested exhaustively: unit (construction, arithmetic, division, allocation, compare/equality, format,
  validation) + jqwik property tests (allocation conservation, parse round-trip).

Mirrors go-boilerplate ADR-0020. Full design: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §3.
```
- [ ] **Step 3: commit:**
```bash
git add docs/decisions/0011-money-representation.md
git commit -m "docs: ADR 0011 money representation (acme-money)"
```

---

## Done criteria for BANK-0

- `gradle :starters:acme-money:test` green; `gradle build` green (no regression).
- `Money` covers construction (no float), exact arithmetic, explicit banker's division, Fowler
  allocation (unit-conserving), value equality, format/toMinor, string wire round-trip, DoS bounds.
- jqwik property tests pass (allocation conserves total; string round-trips).
- ADR 0011 recorded.

---

## Self-review notes

- **Spec coverage (§3):** BigDecimal + Asset registry ✓ (T2–T3), no-float ✓ (T3), exact arithmetic ✓
  (T4), explicit/banker's division ✓ (T5), Fowler allocate/split ✓ (T6), same-asset invariant ✓ (T4/T7),
  value equality ✓ (T7), NUMERIC+asset storage — JPA mapping DEFERRED to BANK-1 (documented in plan
  header + ADR), string wire ✓ (T8), DoS bounds ✓ (T9), full test suite incl. property/fuzz ✓ (T10).
- **Type consistency:** `Money.of/ofMinor/ofMajor/zero`, `add/subtract/multiply/negate/abs`,
  `divide(BigDecimal,int[,RoundingMode])`, `allocate(int...)`/`split(int)`, `compareTo/min/max`,
  `equals/hashCode`, `toAmountString/format/toMinor`, `asset()` — used consistently across tasks and
  tests. `Assets.USD/EUR/JPY/BHD/ETH/USDC`, `Assets.of(String)`.
- **No placeholders.** Every step has concrete code/commands.
- **Purity:** `acme-money` has no Spring/DB/Avro dependencies; the DB round-trip is honestly deferred to
  BANK-1 where Money is mapped on a real entity with Testcontainers.
