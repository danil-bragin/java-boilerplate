# BANK-11: strong-consistency fix — concurrent overdraft Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Close a confirmed Critical money-correctness bug: concurrent DISTINCT transfers debiting the SAME source account can both pass the read-then-write fund check under READ COMMITTED and overdraw the account (balance goes negative). Serialize postings on the source account with a pessimistic row lock so the derived-balance check is correct under concurrency — while keeping balances purely derived (no materialization).

**Architecture:** The money mutation (`PostTransferHandler` → `JpaLedger.save`) is `StronglyConsistent` (one ACID tx) but the fund check (`ledger.balance` SUM → compare → insert) is a non-atomic read-modify-write with no lock on the source account. Fix: acquire a `PESSIMISTIC_WRITE` lock on the source `account` row at the start of the posting transaction. Concurrent debits on that account then serialize (the second blocks until the first commits, then its SUM sees the committed debit and correctly rejects). Only the source is locked (the destination is only credited — no balance constraint — so a single lock per tx means no lock-ordering / deadlock concern). Balance stays derived: the account row is a serialization anchor, not a stored balance.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Data JPA `@Lock(PESSIMISTIC_WRITE)`, Postgres (READ COMMITTED), Testcontainers, JUnit5.

> Found in the strong-vs-eventual review of the saga. Builds on BANK-0..10.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up (Postgres postgres:16-alpine cached). `gradle :examples:acme-bank:accounts:spotlessApply` before each commit.

---

## Task 1: failing regression test — concurrent distinct transfers must not overdraw (TDD)

**Files:** `examples/acme-bank/accounts/src/test/java/com/acme/bank/accounts/ConcurrentDebitIT.java`.

- [ ] **Step 1:** Write `ConcurrentDebitIT` (Postgres Testcontainer, full Spring context like `PostTransferIT`, `spring.kafka.listener.auto-startup=false`):
  - Seed a source account funded with exactly `100.00 USD` (open it via `OpenAccountCommand` with a 100.00 deposit, or insert account + a +100 opening entry the way `PostTransferIT` seeds funds — match the existing fixture style) and a destination account (same asset, 0 balance).
  - Launch **8 threads**, each sending a `PostTransferCommand` with a DISTINCT `transferId` (`"concurrent-"+i`) debiting `80.00 USD` from the same source to the dest, all released together via a `CountDownLatch`. Send through the `Pipeline` (so `StronglyConsistent` wraps each in its own tx), collecting each `PostTransferResult`.
  - **Assert the money invariant holds:**
    - exactly **one** result is `posted` and the rest are `rejected("INSUFFICIENT_FUNDS")` (100 only funds one 80 debit).
    - the source's derived balance (`ledger.balance(source, USD)`) is `20.00` and **never negative**.
    - the `ledger_entry` table has exactly **2** rows for this source's transfers (the single successful posting's two legs — source+dest), and `posting` has exactly **1** row — proving no second debit was written.
- [ ] **Step 2: run, FAIL** — `gradle :examples:acme-bank:accounts:test --tests "*ConcurrentDebitIT"`. Expected: MORE than one `posted` (overdraft) / balance negative / >2 entries — the current race. (If it flakes to passing occasionally, rerun to demonstrate the failure; the race is real under READ COMMITTED.)

---

## Task 2: pessimistic lock the source account in the posting tx (fix)

**Files:** `accounts/.../domain/Accounts.java` (port: add `findByIdForUpdate`), `accounts/.../adapter/out/persistence/AccountJpaRepository.java` (or wherever the Spring Data repo lives) + `JpaAccounts.java`, `accounts/.../application/PostTransferHandler.java`.

- [ ] **Step 1:** Add a lock-acquiring finder to the `Accounts` port:
```java
    /** Load the account taking a PESSIMISTIC_WRITE row lock — serializes concurrent postings on it. */
    Optional<Account> findByIdForUpdate(AccountId id);
```
- [ ] **Step 2:** Implement it on the JPA side. On the Spring Data repository interface:
```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountJpaEntity a where a.id = :id")
    Optional<AccountJpaEntity> findByIdForUpdate(@Param("id") String id);
```
  (imports `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`/`Query`/`Param`.) Map it through `JpaAccounts.findByIdForUpdate` to the domain `Account` exactly like the existing `findById` mapping.
- [ ] **Step 3:** In `PostTransferHandler.handle`, lock the SOURCE account before the balance check (the lock is held to tx end by the surrounding `StronglyConsistent` transaction):
```java
        // Serialize concurrent debits on the source: take a write lock so the derived-balance
        // read-modify-write below cannot interleave with another posting on the same account.
        Account source = accounts
                .findByIdForUpdate(sourceId)
                .orElseThrow(() -> new AccountNotFoundException(sourceId));
        Account dest = accounts.findById(destId).orElseThrow(() -> new AccountNotFoundException(destId));
```
  (Replace the current non-locking `findById(sourceId)`. Keep `dest` as a plain `findById` — the destination is only credited, no overdraft possible, so locking it is unnecessary and a single lock per tx avoids any deadlock/lock-ordering concern.) The rest (operational check, asset check, balance check, `ledger.save`) is unchanged — but now runs under the source lock.
- [ ] **Step 4:** Keep the `existsByTransferId` idempotency short-circuit at the top (it still guards same-transfer redelivery cheaply, before taking the lock).
- [ ] **Step 5: run, PASS** — `gradle :examples:acme-bank:accounts:test --tests "*ConcurrentDebitIT"` → exactly one posted, balance 20.00, 2 entries / 1 posting. Run the full accounts suite (`PostTransferIT`, `OpenAccountIT`, `AccountApiIT`, etc.) → all green (the lock must not break the single-threaded paths or the same-transferId double-post test).
- [ ] **Step 6: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "fix(accounts): pessimistic-lock source account on posting — prevent concurrent overdraft (strong consistency at the money mutation)"
```

---

## Task 3: deadlock sanity + docs + ADR

**Files:** `accounts/.../ConcurrentDebitIT.java` (add a cross-pair case), ADR `docs/decisions/0022-posting-strong-consistency.md`.

- [ ] **Step 1:** Add a second test method `crossDirectionTransfersDoNotDeadlock`: two accounts A,B each funded; concurrently post A→B and B→A (distinct transferIds). Assert both complete (posted or cleanly rejected, no deadlock/timeout, test finishes within a few seconds). This proves the single-source-lock design has no lock-ordering deadlock (each tx locks only its own source).
- [ ] **Step 2: run, PASS.**
- [ ] **Step 3:** ADR `0022-posting-strong-consistency.md` — decision: the money mutation is the single strong-consistency point in an otherwise eventually-consistent saga; the overdraft race (read-then-write on a derived balance under READ COMMITTED) is closed by a `PESSIMISTIC_WRITE` lock on the source account, not by materializing a balance (the derived-balance invariant stands — the row lock only serializes). Document why only the source is locked (no deadlock), and the alternatives considered (SERIALIZABLE + retry; a versioned/materialized balance with a CHECK constraint — rejected per the no-materialization constraint). Note the throughput trade-off (postings on a hot account serialize) and that this is correct for money.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts docs/decisions/0022-posting-strong-consistency.md
git commit -m "test(accounts): cross-direction no-deadlock case + ADR 0022 posting strong consistency"
```

---

## Task 4: full build

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (the new ConcurrentDebitIT green; all existing ITs green).

---

## Done criteria for BANK-11

- Concurrent distinct transfers on one account can no longer overdraw it: exactly one of N racing debits succeeds, the source balance never goes negative, and only the successful posting's entries are written.
- The fix is a source-account `PESSIMISTIC_WRITE` lock; balances remain purely derived (no materialization).
- No deadlock under cross-direction concurrent transfers.
- ADR 0022 documents the strong-consistency boundary.
- `gradle build` green.

---

## Self-review notes

- **Root cause:** `PostTransferHandler` does `balance = ledger.balance(...)` (SUM) → compare → `ledger.save(...)` with no lock; `TransactionMiddleware` uses default READ COMMITTED; `@Lock`/`FOR UPDATE`/`@Version` absent. Two distinct-transferId debits on one account both read the pre-debit balance and both insert → overdraft. The posting PK anchor only dedups the SAME transferId.
- **Type consistency:** `Accounts.findByIdForUpdate` (port) → repo `@Lock(PESSIMISTIC_WRITE)` query → `JpaAccounts` mapping → used in `PostTransferHandler`. Returns the same domain `Account` as `findById`.
- **No placeholders:** concrete lock annotation + handler change + 8-thread test.
- **Standing constraints honored:** balance stays derived (the lock is a serialization point, NOT a stored balance — no materialized view). Σ=0 per posting unchanged.
- **Risk:** the PESSIMISTIC_WRITE lock requires the call to run inside the `StronglyConsistent` transaction (it does — TransactionMiddleware wraps it); a lock outside a tx is a no-op. Verify the lock is taken within the tx (the test proves serialization). Hot-account throughput serializes — acceptable and correct for money. `existsByTransferId` stays before the lock to keep redelivery cheap.
