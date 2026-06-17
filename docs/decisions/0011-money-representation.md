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
