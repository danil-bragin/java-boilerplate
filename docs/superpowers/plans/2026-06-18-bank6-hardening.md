# BANK-6: hardening follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Close the review-minor gaps to production grade: make the `acme-web` idempotency store concurrency-safe + bounded (reserve-on-first + Caffeine TTL, 409 on in-progress); enforce account operational status in the ledger; remove dead saga enum surface; strengthen the redelivery ITs to assert single outbound-event emission.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Caffeine, acme-web/cqrs, Testcontainers, JUnit5.

> Spec: review minors from BANK-1/BANK-4/BANK-5. Builds on BANK-0..5.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Docker up, Postgres/Redpanda cached. `gradle <module>:spotlessApply` before each commit.

---

## Task 1: idempotency store — reserve-on-first + TTL (TDD) — concurrency-safe

**Files:** `IdempotencyStore.java` (extend), `InMemoryIdempotencyStore.java` (Caffeine), `IdempotencyFilter.java` (reserve flow), acme-web autoconfigure `build.gradle.kts` (caffeine dep), tests.

- [ ] **Step 1:** Add Caffeine to `starters/acme-web-spring-boot-autoconfigure/build.gradle.kts`:
```kotlin
    api(libs.caffeine)
```
- [ ] **Step 2: failing tests** — extend `IdempotencyFilterTest` with:
  - `concurrentFirstRequestsExecuteHandlerOnce`: two threads, same key, latch-synchronized through the filter against a handler that sleeps briefly then returns 201; assert the handler runs EXACTLY once and the loser gets either the replayed 201 or a 409 (in-progress) — never a second handler execution. (Use a real `InMemoryIdempotencyStore`, an `ExecutorService`, a `CountDownLatch`.)
  - `inProgressKeyReturns409`: reserve a key (simulate in-progress, not yet completed), then a request with that key returns 409 Conflict.
- [ ] **Step 3: run, FAIL** — `gradle :starters:acme-web-spring-boot-autoconfigure:test --tests "*IdempotencyFilterTest"` → FAIL.
- [ ] **Step 4: extend `IdempotencyStore`** — add a reservation API:
```java
package com.acme.web;

import java.util.Optional;

public interface IdempotencyStore {

    /** A completed response for the key, if any. */
    Optional<StoredResponse> find(String key);

    /** Atomically reserve the key as in-progress. Returns true only for the FIRST caller. */
    boolean reserve(String key);

    /** Mark the key complete with its response. */
    void complete(String key, StoredResponse response);

    /** Release a reservation (e.g. on a non-cacheable outcome) so it can be retried. */
    void release(String key);

    record StoredResponse(int status, String contentType, byte[] body) {}
}
```
- [ ] **Step 5: `InMemoryIdempotencyStore` (Caffeine, reserve)** —
```java
package com.acme.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;

/**
 * In-process idempotency store with a TTL (bounded growth) and a reserve-on-first marker so
 * concurrent first-requests for the same key cannot both execute. Override with a shared (e.g.
 * Redis) store for multi-instance deployments.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private enum State {
        IN_PROGRESS,
        COMPLETED
    }

    private record Entry(State state, StoredResponse response) {}

    private final Cache<String, Entry> cache;

    public InMemoryIdempotencyStore() {
        this(Duration.ofHours(24));
    }

    public InMemoryIdempotencyStore(Duration ttl) {
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        Entry entry = cache.getIfPresent(key);
        return entry != null && entry.state() == State.COMPLETED
                ? Optional.of(entry.response())
                : Optional.empty();
    }

    @Override
    public boolean reserve(String key) {
        // atomic put-if-absent: only the first caller succeeds
        return cache.asMap().putIfAbsent(key, new Entry(State.IN_PROGRESS, null)) == null;
    }

    @Override
    public void complete(String key, StoredResponse response) {
        cache.put(key, new Entry(State.COMPLETED, response));
    }

    @Override
    public void release(String key) {
        cache.asMap().computeIfPresent(key, (k, e) -> e.state() == State.IN_PROGRESS ? null : e);
    }
}
```
- [ ] **Step 6: rework `IdempotencyFilter`** —
```java
package com.acme.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotent retries of UNSAFE requests carrying an {@code Idempotency-Key}: the first request
 * reserves the key and executes; a later retry replays the stored 2xx response; a concurrent
 * request whose key is still in-progress gets 409 Conflict. Only 2xx outcomes are cached (4xx/5xx
 * release the reservation so the client can retry after fixing the request). The default store is
 * in-process with a TTL — use a shared store for multi-instance deployments.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || !isUnsafe(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        var completed = store.find(key);
        if (completed.isPresent()) {
            writeStored(response, completed.get());
            return;
        }
        if (!store.reserve(key)) {
            // either it completed between find() and reserve() — replay — or it's still in progress -> 409
            var raced = store.find(key);
            if (raced.isPresent()) {
                writeStored(response, raced.get());
            } else {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
            }
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean cached = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (status >= 200 && status < 300) {
                store.complete(
                        key,
                        new IdempotencyStore.StoredResponse(
                                status, wrapper.getContentType(), wrapper.getContentAsByteArray()));
                cached = true;
            }
        } finally {
            if (!cached) {
                store.release(key);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private void writeStored(HttpServletResponse response, IdempotencyStore.StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.getOutputStream().write(stored.body());
    }

    private boolean isUnsafe(String method) {
        return "POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method);
    }
}
```
- [ ] **Step 7: run, PASS** — `gradle :starters:acme-web-spring-boot-autoconfigure:test` → green (existing + new concurrency tests).
- [ ] **Step 8: commit**
```bash
gradle :starters:acme-web-spring-boot-autoconfigure:spotlessApply
git add starters/acme-web-spring-boot-autoconfigure
git commit -m "fix(acme-web): concurrency-safe idempotency store (reserve-on-first + Caffeine TTL, 409 on in-progress)"
```

---

## Task 2: accounts — enforce operational status (TDD)

**Files:** `PostTransferHandler.java`, `Account` already has `isOperational()`; add reason + test.

- [ ] **Step 1: failing test** — add to `PostTransferIT` a case: a FROZEN source account → `PostTransferResult.rejected(..., "ACCOUNT_NOT_OPERATIONAL")`, no entries written. Seed a FROZEN account: `INSERT INTO account(id, iban, status) VALUES ('frozen','IBAN','FROZEN')`.
- [ ] **Step 2: run, FAIL** — the current handler ignores status.
- [ ] **Step 3: enforce** — in `PostTransferHandler.handle`, after loading source + dest, before the fund check:
```java
        if (!source.isOperational() || !dest.isOperational()) {
            return PostTransferResult.rejected(command.transferId(), "ACCOUNT_NOT_OPERATIONAL");
        }
```
(`dest` is currently loaded only for existence; capture it: `Account dest = accounts.findById(destId).orElseThrow(...)`.)
- [ ] **Step 4: run, PASS** — `gradle :examples:acme-bank:accounts:test --tests "*PostTransferIT"` → green.
- [ ] **Step 5: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): reject postings to/from non-operational accounts (ACCOUNT_NOT_OPERATIONAL)"
```

---

## Task 3: transfers — drop dead saga enum surface

**Files:** `TransferStatus.java`, `Transfer.java`.

- [ ] **Step 1:** Remove the unused `SCREENING` and `REJECTED` constants from `TransferStatus` (the live saga goes REQUESTED→APPROVED→POSTING→COMPLETED, with `FAILED` for the rejected/failed branch). In `Transfer.approve()`/`reject()`, remove the `SCREENING` predecessor allowance (keep only `REQUESTED`). Verify no test references `SCREENING`/`REJECTED`.
- [ ] **Step 2:** `gradle :examples:acme-bank:transfers:test` → green (the state machine + saga ITs unaffected since those statuses were never used).
- [ ] **Step 3: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
git add examples/acme-bank/transfers
git commit -m "refactor(transfers): drop unused SCREENING/REJECTED saga states"
```

---

## Task 4: strengthen redelivery ITs — assert single outbound emission

**Files:** `TransferAdvanceIT.java`, `NotificationIT.java`.

- [ ] **Step 1:** In `TransferAdvanceIT`'s redelivery test, after producing the same `TransferScreened(approved)` twice, subscribe to `posting-requested` and assert EXACTLY ONE record is observed for that transferId within the window (poll, count records with that key == 1). (Currently it only asserts the `processed_messages` row count.)
- [ ] **Step 2:** In `NotificationIT`'s redelivery test, it already asserts one `notification` row — also assert no second processing by confirming the `processed_messages` count is 1 (if not already) and the notification row count is exactly 1 (keep).
- [ ] **Step 3:** `gradle :examples:acme-bank:transfers:test --tests "*TransferAdvanceIT"` and `:notifications:test --tests "*NotificationIT"` → green.
- [ ] **Step 4: commit**
```bash
gradle :examples:acme-bank:transfers:spotlessApply
gradle :examples:acme-bank:notifications:spotlessApply
git add examples/acme-bank/transfers/src/test examples/acme-bank/notifications/src/test
git commit -m "test(bank): redelivery ITs assert single outbound emission (no double-emit)"
```

---

## Task 5: full build

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL.
- [ ] **Step 2:** (No ADR — these are hardening tweaks; the existing ADRs cover the mechanisms.)

---

## Done criteria for BANK-6

- idempotency filter is concurrency-safe (reserve-on-first, 409 on in-progress) + TTL-bounded; tested under concurrency.
- accounts rejects non-operational accounts.
- dead saga enum surface removed.
- redelivery ITs assert single outbound emission.
- `gradle build` green.

---

## Self-review notes

- **Coverage:** BANK-4 idempotency minors (concurrency race + unbounded) ✓; BANK-1 account-status ✓; BANK-5 dead enum ✓ + redelivery outbound assertion ✓.
- **Type consistency:** `IdempotencyStore.{find,reserve,complete,release}` used by `IdempotencyFilter` + `InMemoryIdempotencyStore`; `PostTransferResult.rejected`; `Account.isOperational`.
- **No placeholders.** Concrete code/tests.
- **Risk:** the idempotency filter's find()→reserve() race is handled (re-check find on reserve failure). `release` on non-2xx lets the client retry. Caffeine TTL bounds growth.
