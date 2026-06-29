# acme-money

Plain Java library of immutable value types for money. A `Money` is an arbitrary-precision
decimal bound to an `Asset`, with exact arithmetic, explicit rounding, and unit-conserving
allocation. No floating-point construction path; no Spring dependency at runtime (Spring/JUnit are
test-only). Mirrors the Go platform's `money` package.

## What it provides
- `Asset` — record `(String code, int scale)`; validates non-blank code and `scale >= 0`. `scale` is
  the minor-unit decimal places (e.g. USD 2, JPY 0, ETH 18).
- `Assets` — in-code registry (no DB lookup). Constants `USD`, `EUR`, `JPY`, `BHD`, `ETH`, `USDC`;
  `Assets.of(code)` (throws on unknown), `Assets.find(code)` returns `Optional<Asset>`.
- `Money` — immutable, `Comparable<Money>`. Factories: `of(String, Asset)`, `ofMinor(BigInteger, Asset)`,
  `ofMajor(long, Asset)`, `zero(Asset)`. No float path; string parsing is magnitude-bounded
  (> 1000 significant digits or `|scale| > 256` rejected).
  - Arithmetic: `add`, `subtract`, `multiply(BigDecimal)`, `negate`, `abs` (exact; scale grows).
  - Division: `divide(BigDecimal, int scale, RoundingMode)` and `divide(BigDecimal, int scale)`
    (defaults to `HALF_EVEN`, banker's rounding).
  - Allocation: `allocate(int... ratios)` and `split(int n)` — Fowler allocation, sign-aware,
    conserves every minor unit; rounds once at the asset's scale.
  - Inspect/convert: `asset`, `signum`, `isZero`, `isPositive`, `isNegative`, `min`, `max`,
    `toMinor()` (BigInteger), `toAmountString()`, `format()` (e.g. `"10.00 USD"`).
  - Equality is value-based and scale-insensitive (`10.0 USD` equals `10.00 USD`).
- `CurrencyMismatchException` — `RuntimeException` thrown by binary ops across differing assets;
  there is no implicit currency coercion.

## Usage
```kotlin
implementation("acme-bank:acme-money")
```
Plain library dependency — construct `Money` via the static factories and operate on it directly;
no autoconfiguration or Spring context involved.

## See also
- ADR-0011 Money representation (`acme-money`), root README
