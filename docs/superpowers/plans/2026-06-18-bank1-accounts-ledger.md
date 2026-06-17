# BANK-1: accounts + double-entry ledger (deep hexagonal core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the `accounts` service — the protected hexagonal+DDD core of `acme-bank`: a double-entry ledger on `acme-money` where posting a transfer is atomic (one DB transaction), balanced (Σ entries == 0), idempotent (by transfer id), and fund-checked. Balance is purely derived (SUM of entries). No Kafka yet (the messaging adapter wires in BANK-2); BANK-1 proves the core + persistence on Testcontainers, with ArchUnit fitness functions enforcing the architecture.

**Architecture:** Hexagonal. `domain/` (no Spring): value objects (`AccountId`, `Iban`, `AccountStatus`), aggregates (`Account`, `Posting` with the Σ=0 invariant, `LedgerEntry`), out-ports (`Accounts`, `Ledger`), domain exceptions — jMolecules stereotypes. `application/`: `PostTransfer` use case as an `acme-cqrs` `StronglyConsistent` command handler. `adapter/out/persistence/`: JPA entities mapping `Money` via a new reusable `MoneyAmount` `@Embeddable` (amount `NUMERIC` + asset `VARCHAR`) added to `acme-persistence`; Spring Data repos implementing the ports; Flyway schema. ArchUnit + jmolecules-archunit fitness functions run in the build.

**Tech Stack:** Java 21, Spring Boot 3.5.6, `acme-money`, `acme-persistence`, `acme-cqrs`, Spring Data JPA, Flyway, Testcontainers Postgres, JUnit5 + AssertJ, ArchUnit, jMolecules.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §5/§6/§12. Builds on BANK-0 (money) + BANK-0.5 (skeleton/conventions). Messaging adapters + the saga wiring are BANK-2.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21 toolchain; Docker up, `postgres:16-alpine` cached. `gradle <module>:spotlessApply` before each commit. Spotless `removeUnusedImports` active.

---

## File structure

```
settings.gradle.kts                                MODIFY: include examples:acme-bank:accounts
starters/acme-persistence-spring-boot-autoconfigure/
  src/main/java/com/acme/persistence/MoneyAmount.java   NEW (@Embeddable Money mapping)  ← starter improvement
  src/main/java/com/acme/persistence/AssetLookup.java   NEW (functional seam for asset resolution)
examples/acme-bank/accounts/
  build.gradle.kts                                 NEW
  src/main/java/com/acme/bank/accounts/
    domain/AccountId.java, Iban.java, AccountStatus.java, Account.java,
           LedgerEntry.java, Posting.java, Accounts.java (port), Ledger.java (port),
           AccountNotFoundException.java, InsufficientFundsException.java
    application/PostTransferCommand.java, PostTransferResult.java, PostTransferHandler.java
    adapter/out/persistence/{AccountJpaEntity, PostingJpaEntity, LedgerEntryJpaEntity,
           AccountJpaRepository, PostingJpaRepository, LedgerEntryJpaRepository,
           JpaAccounts, JpaLedger}.java
    AccountsApplication.java
  src/main/resources/application.yaml
  src/main/resources/db/migration/postgresql/V1__accounts.sql
  src/main/resources/db/migration/oracle/V1__accounts.sql
  src/test/java/com/acme/bank/accounts/
    domain/PostingTest.java, AccountTest.java
    ArchitectureTest.java (ArchUnit fitness)
    application/PostTransferIT.java (Testcontainers)
    adapter/out/persistence/MoneyPersistenceIT.java (Testcontainers — NUMERIC+asset round-trip)
docs/decisions/0013-double-entry-ledger.md         NEW
```

---

## Task 1: `MoneyAmount` @Embeddable in acme-persistence (TDD) — starter improvement

**Files:** `MoneyAmount.java`, `AssetLookup.java`, test in acme-persistence.

- [ ] **Step 1:** Add `compileOnly`/`api` money dep — in `starters/acme-persistence-spring-boot-autoconfigure/build.gradle.kts` `dependencies { }` add:
```kotlin
    api(project(":starters:acme-money"))
```
- [ ] **Step 2: failing test** — `starters/acme-persistence-spring-boot-autoconfigure/src/test/java/com/acme/persistence/MoneyAmountTest.java`:
```java
package com.acme.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.money.Assets;
import com.acme.money.Money;
import org.junit.jupiter.api.Test;

class MoneyAmountTest {

    @Test
    void roundTripsMoney() {
        Money money = Money.of("1234.56", Assets.USD);
        MoneyAmount embeddable = MoneyAmount.from(money);
        assertThat(embeddable.getAmount()).isEqualByComparingTo("1234.56");
        assertThat(embeddable.getAsset()).isEqualTo("USD");
        assertThat(embeddable.toMoney(Assets::of)).isEqualTo(money);
    }
}
```
- [ ] **Step 3: run, FAIL** — `gradle :starters:acme-persistence-spring-boot-autoconfigure:test --tests "*MoneyAmountTest"` → FAIL.
- [ ] **Step 4: `AssetLookup`** — `starters/acme-persistence-spring-boot-autoconfigure/src/main/java/com/acme/persistence/AssetLookup.java`:
```java
package com.acme.persistence;

import com.acme.money.Asset;

/** Resolves an asset code to an {@link Asset} (e.g. {@code Assets::of}); keeps persistence decoupled. */
@FunctionalInterface
public interface AssetLookup {
    Asset resolve(String code);
}
```
- [ ] **Step 5: `MoneyAmount`** — `starters/acme-persistence-spring-boot-autoconfigure/src/main/java/com/acme/persistence/MoneyAmount.java`:
```java
package com.acme.persistence;

import com.acme.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** JPA embeddable mapping a {@link Money} to two columns: {@code NUMERIC amount} + {@code VARCHAR asset}. */
@Embeddable
public class MoneyAmount {

    @Column(name = "amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "asset", nullable = false, length = 16)
    private String asset;

    protected MoneyAmount() {
        // for JPA
    }

    private MoneyAmount(BigDecimal amount, String asset) {
        this.amount = amount;
        this.asset = asset;
    }

    public static MoneyAmount from(Money money) {
        return new MoneyAmount(new BigDecimal(money.toAmountString()), money.asset().code());
    }

    public Money toMoney(AssetLookup assets) {
        return Money.of(amount.toPlainString(), assets.resolve(asset));
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getAsset() {
        return asset;
    }
}
```
- [ ] **Step 6: run, PASS** — `gradle :starters:acme-persistence-spring-boot-autoconfigure:test --tests "*MoneyAmountTest"` → PASS.
- [ ] **Step 7: format + commit**
```bash
gradle :starters:acme-persistence-spring-boot-autoconfigure:spotlessApply
git add starters/acme-persistence-spring-boot-autoconfigure
git commit -m "feat(acme-persistence): MoneyAmount @Embeddable (NUMERIC amount + asset) for Money mapping"
```

---

## Task 2: accounts module + domain value objects (TDD)

**Files:** settings (modify), `accounts/build.gradle.kts`, domain VOs + test.

- [ ] **Step 1: settings** — add `"examples:acme-bank:accounts",` to `include(...)`.
- [ ] **Step 2: dirs**
```bash
mkdir -p examples/acme-bank/accounts/src/main/java/com/acme/bank/accounts/domain \
  examples/acme-bank/accounts/src/main/java/com/acme/bank/accounts/application \
  examples/acme-bank/accounts/src/main/java/com/acme/bank/accounts/adapter/out/persistence \
  examples/acme-bank/accounts/src/main/resources/db/migration/postgresql \
  examples/acme-bank/accounts/src/main/resources/db/migration/oracle \
  examples/acme-bank/accounts/src/test/java/com/acme/bank/accounts/domain \
  examples/acme-bank/accounts/src/test/java/com/acme/bank/accounts/application \
  examples/acme-bank/accounts/src/test/java/com/acme/bank/accounts/adapter/out/persistence
```
- [ ] **Step 3: build script** — `examples/acme-bank/accounts/build.gradle.kts`:
```kotlin
plugins {
    id("acme.bank-service-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform:acme-bom")))
    implementation(project(":starters:acme-money"))
    implementation(project(":starters:acme-persistence-spring-boot-starter"))
    implementation(project(":starters:acme-cqrs-spring-boot-starter"))
    testImplementation(project(":starters:acme-test-support"))
    testImplementation(libs.spring.boot.starter.test)
}
```
- [ ] **Step 4: failing domain test** — `examples/acme-bank/accounts/src/test/java/com/acme/bank/accounts/domain/AccountTest.java`:
```java
package com.acme.bank.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    void ibanRejectsBlank() {
        assertThatThrownBy(() -> new Iban("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountIdIsAValue() {
        assertThat(new AccountId("acc-1")).isEqualTo(new AccountId("acc-1"));
    }

    @Test
    void openAccountIsActive() {
        Account account = new Account(new AccountId("acc-1"), new Iban("DE89370400440532013000"));
        assertThat(account.status()).isEqualTo(AccountStatus.OPEN);
        assertThat(account.isOperational()).isTrue();
    }
}
```
- [ ] **Step 5: run, FAIL** — `gradle :examples:acme-bank:accounts:test --tests "*AccountTest"` → FAIL.
- [ ] **Step 6: VOs + Account** — create the files:

`.../domain/AccountId.java`:
```java
package com.acme.bank.accounts.domain;

import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record AccountId(String value) {
    public AccountId {
        Objects.requireNonNull(value, "account id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("account id must not be blank");
        }
    }
}
```
`.../domain/Iban.java`:
```java
package com.acme.bank.accounts.domain;

import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

@ValueObject
public record Iban(String value) {
    public Iban {
        Objects.requireNonNull(value, "iban");
        if (value.isBlank()) {
            throw new IllegalArgumentException("iban must not be blank");
        }
    }
}
```
`.../domain/AccountStatus.java`:
```java
package com.acme.bank.accounts.domain;

public enum AccountStatus {
    OPEN,
    FROZEN,
    CLOSED
}
```
`.../domain/Account.java`:
```java
package com.acme.bank.accounts.domain;

import java.util.Objects;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

@AggregateRoot
public class Account {

    @Identity
    private final AccountId id;

    private final Iban iban;
    private final AccountStatus status;

    public Account(AccountId id, Iban iban) {
        this(id, iban, AccountStatus.OPEN);
    }

    public Account(AccountId id, Iban iban, AccountStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.iban = Objects.requireNonNull(iban, "iban");
        this.status = Objects.requireNonNull(status, "status");
    }

    public AccountId id() {
        return id;
    }

    public Iban iban() {
        return iban;
    }

    public AccountStatus status() {
        return status;
    }

    /** Operational accounts can be debited/credited. */
    public boolean isOperational() {
        return status == AccountStatus.OPEN;
    }
}
```
- [ ] **Step 7: run, PASS** — `gradle :examples:acme-bank:accounts:test --tests "*AccountTest"` → PASS.
- [ ] **Step 8: format + commit**
```bash
gradle :examples:acme-bank:accounts:spotlespotlessApply 2>/dev/null; gradle :examples:acme-bank:accounts:spotlessApply
git add settings.gradle.kts examples/acme-bank/accounts
git commit -m "feat(accounts): module + domain value objects (AccountId, Iban, Account)"
```

---

## Task 3: `Posting` aggregate with Σ=0 invariant (TDD) — the ledger heart

**Files:** `LedgerEntry.java`, `Posting.java`, test `PostingTest.java`.

- [ ] **Step 1: failing test** — `examples/acme-bank/accounts/src/test/java/com/acme/bank/accounts/domain/PostingTest.java`:
```java
package com.acme.bank.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.money.Assets;
import com.acme.money.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostingTest {

    private final AccountId source = new AccountId("acc-src");
    private final AccountId dest = new AccountId("acc-dst");

    @Test
    void transferBuildsTwoBalancedEntries() {
        Posting posting = Posting.transfer("t-1", source, dest, Money.of("100.00", Assets.USD));
        assertThat(posting.entries()).hasSize(2);
        // source debited (negative), destination credited (positive), sum zero
        assertThat(posting.entries().get(0).amount()).isEqualTo(Money.of("-100.00", Assets.USD));
        assertThat(posting.entries().get(1).amount()).isEqualTo(Money.of("100.00", Assets.USD));
        assertThat(posting.transferId()).isEqualTo("t-1");
    }

    @Test
    void postingRejectsUnbalancedEntries() {
        List<LedgerEntry> unbalanced = List.of(
                new LedgerEntry(source, Money.of("-100.00", Assets.USD)),
                new LedgerEntry(dest, Money.of("99.00", Assets.USD)));
        assertThatThrownBy(() -> new Posting("t-2", unbalanced))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance");
    }

    @Test
    void postingRejectsFewerThanTwoEntries() {
        assertThatThrownBy(() ->
                        new Posting("t-3", List.of(new LedgerEntry(source, Money.zero(Assets.USD)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```
- [ ] **Step 2: run, FAIL** — `gradle :examples:acme-bank:accounts:test --tests "*PostingTest"` → FAIL.
- [ ] **Step 3: `LedgerEntry`** — `.../domain/LedgerEntry.java`:
```java
package com.acme.bank.accounts.domain;

import com.acme.money.Money;
import java.util.Objects;
import org.jmolecules.ddd.annotation.ValueObject;

/** One side of a posting: a signed amount applied to an account (debit negative, credit positive). */
@ValueObject
public record LedgerEntry(AccountId accountId, Money amount) {
    public LedgerEntry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
    }
}
```
- [ ] **Step 4: `Posting`** — `.../domain/Posting.java`:
```java
package com.acme.bank.accounts.domain;

import com.acme.money.Money;
import java.util.List;
import java.util.Objects;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

/**
 * An immutable, balanced double-entry transaction. The core invariant: the signed amounts of all
 * entries sum to zero (within one asset). Append-only — corrections are new postings, never updates.
 */
@AggregateRoot
public class Posting {

    @Identity
    private final String transferId;

    private final List<LedgerEntry> entries;

    public Posting(String transferId, List<LedgerEntry> entries) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() < 2) {
            throw new IllegalArgumentException("a posting needs at least two entries");
        }
        Money sum = entries.get(0).amount();
        for (int i = 1; i < entries.size(); i++) {
            sum = sum.add(entries.get(i).amount()); // add() enforces same-asset
        }
        if (!sum.isZero()) {
            throw new IllegalArgumentException("posting entries must balance to zero, got " + sum);
        }
        this.entries = List.copyOf(entries);
    }

    /** Build a two-entry transfer posting: debit source, credit destination. */
    public static Posting transfer(String transferId, AccountId source, AccountId destination, Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }
        return new Posting(
                transferId,
                List.of(new LedgerEntry(source, amount.negate()), new LedgerEntry(destination, amount)));
    }

    public String transferId() {
        return transferId;
    }

    public List<LedgerEntry> entries() {
        return entries;
    }
}
```
- [ ] **Step 5: run, PASS** — `gradle :examples:acme-bank:accounts:test --tests "*PostingTest"` → PASS.
- [ ] **Step 6: format + commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): Posting aggregate with the double-entry Σ=0 invariant"
```

---

## Task 4: domain ports + exceptions

**Files:** `Accounts.java`, `Ledger.java`, `AccountNotFoundException.java`, `InsufficientFundsException.java`.

- [ ] **Step 1:** Create the out-ports and exceptions (no test — they are interfaces; exercised by Task 5's use case).

`.../domain/AccountNotFoundException.java`:
```java
package com.acme.bank.accounts.domain;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(AccountId id) {
        super("account not found: " + id.value());
    }
}
```
`.../domain/InsufficientFundsException.java`:
```java
package com.acme.bank.accounts.domain;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(AccountId id) {
        super("insufficient funds in account: " + id.value());
    }
}
```
`.../domain/Accounts.java` (out-port):
```java
package com.acme.bank.accounts.domain;

import java.util.Optional;

/** Out-port: load accounts. */
public interface Accounts {
    Optional<Account> findById(AccountId id);
}
```
`.../domain/Ledger.java` (out-port):
```java
package com.acme.bank.accounts.domain;

import com.acme.money.Asset;
import com.acme.money.Money;

/** Out-port: persist postings and derive balances from entries. */
public interface Ledger {

    /** Persist a balanced posting atomically. */
    void save(Posting posting);

    /** True if a posting for this transfer already exists (idempotency check). */
    boolean existsByTransferId(String transferId);

    /** Derived balance = SUM of the account's entries for the asset (no materialized balance). */
    Money balance(AccountId accountId, Asset asset);
}
```
- [ ] **Step 2: compile** — `gradle :examples:acme-bank:accounts:compileJava` → BUILD SUCCESSFUL.
- [ ] **Step 3: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): domain out-ports (Accounts, Ledger) + exceptions"
```

---

## Task 5: `PostTransfer` use case — StronglyConsistent CQRS command

**Files:** `PostTransferCommand.java`, `PostTransferResult.java`, `PostTransferHandler.java`.

- [ ] **Step 1:** Create the application use case (an `acme-cqrs` command handler; tested end-to-end in Task 8 with real persistence — the handler's logic is integration-tested there since it depends on the ports).

`.../application/PostTransferResult.java`:
```java
package com.acme.bank.accounts.application;

public record PostTransferResult(String transferId, boolean posted, String reason) {
    public static PostTransferResult posted(String transferId) {
        return new PostTransferResult(transferId, true, null);
    }

    public static PostTransferResult rejected(String transferId, String reason) {
        return new PostTransferResult(transferId, false, reason);
    }
}
```
`.../application/PostTransferCommand.java`:
```java
package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.cqrs.StronglyConsistent;
import com.acme.money.Money;

/** Post a transfer into the ledger. Strongly consistent: the posting is atomic. */
public record PostTransferCommand(
        String transferId, String sourceAccountId, String destinationAccountId, Money amount)
        implements Command<PostTransferResult>, StronglyConsistent {}
```
`.../application/PostTransferHandler.java`:
```java
package com.acme.bank.accounts.application;

import an.awesome.pipelinr.Command;
import com.acme.bank.accounts.domain.Account;
import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.AccountNotFoundException;
import com.acme.bank.accounts.domain.Accounts;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Money;
import org.springframework.stereotype.Component;

@Component
public class PostTransferHandler implements Command.Handler<PostTransferCommand, PostTransferResult> {

    private final Accounts accounts;
    private final Ledger ledger;

    public PostTransferHandler(Accounts accounts, Ledger ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Override
    public PostTransferResult handle(PostTransferCommand command) {
        // Idempotent: a posting for this transfer already applied -> treat as success, no double-post.
        if (ledger.existsByTransferId(command.transferId())) {
            return PostTransferResult.posted(command.transferId());
        }

        AccountId sourceId = new AccountId(command.sourceAccountId());
        AccountId destId = new AccountId(command.destinationAccountId());
        Money amount = command.amount();

        Account source = accounts.findById(sourceId).orElseThrow(() -> new AccountNotFoundException(sourceId));
        accounts.findById(destId).orElseThrow(() -> new AccountNotFoundException(destId));

        Money balance = ledger.balance(sourceId, amount.asset());
        if (balance.compareTo(amount) < 0) {
            return PostTransferResult.rejected(command.transferId(), "INSUFFICIENT_FUNDS");
        }

        ledger.save(Posting.transfer(command.transferId(), sourceId, destId, amount));
        return PostTransferResult.posted(command.transferId());
    }
}
```
> `StronglyConsistent` makes `acme-cqrs`'s `TransactionMiddleware` wrap the whole handler in one DB transaction — the idempotency check, balance read, and posting save commit atomically.
- [ ] **Step 2: compile** — `gradle :examples:acme-bank:accounts:compileJava` → BUILD SUCCESSFUL.
- [ ] **Step 3: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): PostTransfer StronglyConsistent CQRS use case (atomic, idempotent, fund-checked)"
```

---

## Task 6: persistence adapter (JPA entities + Money mapping + ports impl) + Flyway

**Files:** JPA entities, repos, `JpaAccounts`, `JpaLedger`, `AccountsApplication`, `application.yaml`, migrations.

- [ ] **Step 1: JPA entities** — under `.../adapter/out/persistence/`:

`AccountJpaEntity.java`:
```java
package com.acme.bank.accounts.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
class AccountJpaEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "iban", nullable = false)
    private String iban;

    @Column(name = "status", nullable = false)
    private String status;

    protected AccountJpaEntity() {}

    AccountJpaEntity(String id, String iban, String status) {
        this.id = id;
        this.iban = iban;
        this.status = status;
    }

    String getId() {
        return id;
    }

    String getIban() {
        return iban;
    }

    String getStatus() {
        return status;
    }
}
```
`LedgerEntryJpaEntity.java` (uses the `MoneyAmount` embeddable for amount NUMERIC+asset):
```java
package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.persistence.MoneyAmount;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_entry")
class LedgerEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entry_seq")
    @SequenceGenerator(name = "ledger_entry_seq", sequenceName = "ledger_entry_seq", allocationSize = 50)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private String transferId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Embedded
    private MoneyAmount amount;

    protected LedgerEntryJpaEntity() {}

    LedgerEntryJpaEntity(String transferId, String accountId, MoneyAmount amount) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.amount = amount;
    }

    String getAccountId() {
        return accountId;
    }

    MoneyAmount getAmount() {
        return amount;
    }
}
```
- [ ] **Step 2: repositories** —

`AccountJpaRepository.java`:
```java
package com.acme.bank.accounts.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, String> {}
```
`LedgerEntryJpaRepository.java`:
```java
package com.acme.bank.accounts.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryJpaEntity, Long> {

    boolean existsByTransferId(String transferId);

    @Query("select coalesce(sum(e.amount.amount), 0) from LedgerEntryJpaEntity e "
            + "where e.accountId = :accountId and e.amount.asset = :asset")
    BigDecimal sumAmount(@Param("accountId") String accountId, @Param("asset") String asset);

    List<LedgerEntryJpaEntity> findByTransferId(String transferId);
}
```
- [ ] **Step 3: port implementations** —

`JpaAccounts.java`:
```java
package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.bank.accounts.domain.Account;
import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.AccountStatus;
import com.acme.bank.accounts.domain.Accounts;
import com.acme.bank.accounts.domain.Iban;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JpaAccounts implements Accounts {

    private final AccountJpaRepository repository;

    JpaAccounts(AccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return repository
                .findById(id.value())
                .map(e -> new Account(
                        new AccountId(e.getId()), new Iban(e.getIban()), AccountStatus.valueOf(e.getStatus())));
    }
}
```
`JpaLedger.java`:
```java
package com.acme.bank.accounts.adapter.out.persistence;

import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.LedgerEntry;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Asset;
import com.acme.money.Money;
import com.acme.persistence.MoneyAmount;
import java.math.BigInteger;
import org.springframework.stereotype.Component;

@Component
class JpaLedger implements Ledger {

    private final LedgerEntryJpaRepository entries;

    JpaLedger(LedgerEntryJpaRepository entries) {
        this.entries = entries;
    }

    @Override
    public void save(Posting posting) {
        for (LedgerEntry entry : posting.entries()) {
            entries.save(new LedgerEntryJpaEntity(
                    posting.transferId(), entry.accountId().value(), MoneyAmount.from(entry.amount())));
        }
    }

    @Override
    public boolean existsByTransferId(String transferId) {
        return entries.existsByTransferId(transferId);
    }

    @Override
    public Money balance(AccountId accountId, Asset asset) {
        java.math.BigDecimal sum = entries.sumAmount(accountId.value(), asset.code());
        return Money.of(sum.toPlainString(), asset);
    }
}
```
- [ ] **Step 4: Spring Boot app** — `.../AccountsApplication.java`:
```java
package com.acme.bank.accounts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AccountsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountsApplication.class, args);
    }
}
```
- [ ] **Step 5: config** — `.../src/main/resources/application.yaml`:
```yaml
spring:
  application:
    name: accounts
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    locations: classpath:db/migration/{vendor}
```
- [ ] **Step 6: Postgres migration** — `.../db/migration/postgresql/V1__accounts.sql`:
```sql
CREATE TABLE account (
    id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    iban   VARCHAR(34)  NOT NULL,
    status VARCHAR(16)  NOT NULL
);

CREATE SEQUENCE ledger_entry_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE ledger_entry (
    id          BIGINT        NOT NULL PRIMARY KEY,
    transfer_id VARCHAR(64)   NOT NULL,
    account_id  VARCHAR(64)   NOT NULL,
    amount      NUMERIC(38,18) NOT NULL,
    asset       VARCHAR(16)   NOT NULL
);

CREATE INDEX idx_ledger_entry_account ON ledger_entry (account_id, asset);
CREATE INDEX idx_ledger_entry_transfer ON ledger_entry (transfer_id);
```
- [ ] **Step 7: Oracle migration (reference)** — `.../db/migration/oracle/V1__accounts.sql`:
```sql
CREATE TABLE account (
    id     VARCHAR2(64) NOT NULL PRIMARY KEY,
    iban   VARCHAR2(34) NOT NULL,
    status VARCHAR2(16) NOT NULL
);

CREATE SEQUENCE ledger_entry_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE ledger_entry (
    id          NUMBER(19)     NOT NULL PRIMARY KEY,
    transfer_id VARCHAR2(64)   NOT NULL,
    account_id  VARCHAR2(64)   NOT NULL,
    amount      NUMBER(38,18)  NOT NULL,
    asset       VARCHAR2(16)   NOT NULL
);

CREATE INDEX idx_ledger_entry_account ON ledger_entry (account_id, asset);
CREATE INDEX idx_ledger_entry_transfer ON ledger_entry (transfer_id);
```
- [ ] **Step 8: compile** — `gradle :examples:acme-bank:accounts:compileJava` → BUILD SUCCESSFUL.
- [ ] **Step 9: commit**
```bash
gradle :examples:acme-bank:accounts:spotlessApply
git add examples/acme-bank/accounts
git commit -m "feat(accounts): JPA persistence adapter (Money NUMERIC+asset) + ports impl + Flyway schema"
```

---

## Task 7: ArchUnit fitness functions

**Files:** `ArchitectureTest.java`.

- [ ] **Step 1:** Create `examples/acme-bank/accounts/src/test/java/com/acme/bank/accounts/ArchitectureTest.java`:
```java
package com.acme.bank.accounts;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.acme.bank.accounts");

    @Test
    void domainHasNoSpringOrPersistenceDependencies() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.acme.persistence..");
        rule.check(classes);
    }

    @Test
    void domainDoesNotDependOnAdapters() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @Test
    void applicationDoesNotDependOnAdapters() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter..");
        rule.check(classes);
    }
}
```
- [ ] **Step 2: run** — `gradle :examples:acme-bank:accounts:test --tests "*ArchitectureTest"` → PASS. (If the domain accidentally imports a framework type, the rule fails — fix the violation, do NOT relax the rule.)
- [ ] **Step 3: commit**
```bash
git add examples/acme-bank/accounts/src/test
git commit -m "test(accounts): ArchUnit fitness functions enforcing hexagonal boundaries"
```

---

## Task 8: PostTransfer integration test (Testcontainers) — the crown proof

**Files:** `MoneyPersistenceIT.java`, `PostTransferIT.java`.

- [ ] **Step 1: Money persistence round-trip IT** — `.../adapter/out/persistence/MoneyPersistenceIT.java`:
```java
package com.acme.bank.accounts.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.accounts.domain.AccountId;
import com.acme.bank.accounts.domain.Ledger;
import com.acme.bank.accounts.domain.Posting;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class MoneyPersistenceIT {

    @Autowired
    Ledger ledger;

    @Test
    void savesAndDerivesBalanceWithExactMoney() {
        AccountId a = new AccountId("acc-a");
        AccountId b = new AccountId("acc-b");
        ledger.save(Posting.transfer("t-money", a, b, Money.of("100.05", Assets.USD)));

        assertThat(ledger.balance(a, Assets.USD)).isEqualTo(Money.of("-100.05", Assets.USD));
        assertThat(ledger.balance(b, Assets.USD)).isEqualTo(Money.of("100.05", Assets.USD));
    }
}
```
- [ ] **Step 2: PostTransfer IT (ACID Σ=0, idempotency, insufficient funds)** — `.../application/PostTransferIT.java`:
```java
package com.acme.bank.accounts.application;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class PostTransferIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    JdbcTemplate jdbc;

    private void openAccount(String id) {
        jdbc.update("INSERT INTO account(id, iban, status) VALUES (?, ?, 'OPEN')", id, "IBAN-" + id);
    }

    private void seedBalance(String accountId, String amount) {
        // a balanced opening posting: credit the account, debit a funding account
        jdbc.update(
                "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset) "
                        + "VALUES (nextval('ledger_entry_seq'), ?, ?, ?, 'USD')",
                "seed-" + accountId, accountId, amount);
        jdbc.update(
                "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset) "
                        + "VALUES (nextval('ledger_entry_seq'), ?, 'funding', ?, 'USD')",
                "seed-" + accountId, "-" + amount);
    }

    @Test
    void postsABalancedTransferAndDerivesBalances() {
        openAccount("src");
        openAccount("dst");
        seedBalance("src", "500.00");

        PostTransferResult result = pipeline.send(
                new PostTransferCommand("t-1", "src", "dst", Money.of("120.00", Assets.USD)));

        assertThat(result.posted()).isTrue();
        // src: 500 - 120 = 380 ; dst: +120
        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE transfer_id = 't-1'", Long.class);
        assertThat(entries).isEqualTo(2L);
        // sum of ALL entries for the transfer is zero (double-entry)
        java.math.BigDecimal sum = jdbc.queryForObject(
                "SELECT sum(amount) FROM ledger_entry WHERE transfer_id = 't-1'", java.math.BigDecimal.class);
        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void rejectsInsufficientFundsWithoutMovingMoney() {
        openAccount("poor");
        openAccount("rich");
        seedBalance("poor", "10.00");

        long before = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        PostTransferResult result = pipeline.send(
                new PostTransferCommand("t-2", "poor", "rich", Money.of("100.00", Assets.USD)));

        assertThat(result.posted()).isFalse();
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        long after = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        assertThat(after).isEqualTo(before); // no entries written
    }

    @Test
    void postingIsIdempotentByTransferId() {
        openAccount("s2");
        openAccount("d2");
        seedBalance("s2", "500.00");

        pipeline.send(new PostTransferCommand("t-3", "s2", "d2", Money.of("50.00", Assets.USD)));
        pipeline.send(new PostTransferCommand("t-3", "s2", "d2", Money.of("50.00", Assets.USD))); // retry

        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE transfer_id = 't-3'", Long.class);
        assertThat(entries).isEqualTo(2L); // applied once, not twice
    }
}
```
- [ ] **Step 3: run** — `gradle :examples:acme-bank:accounts:test --tests "*MoneyPersistenceIT" --tests "*PostTransferIT"` → PASS. Then the full module suite `gradle :examples:acme-bank:accounts:test` → green.
> Debug: if `ddl-auto: validate` fails, the `MoneyAmount` embeddable columns (`amount` NUMERIC(38,18), `asset` VARCHAR) must match the Flyway DDL exactly. If the `sumAmount` query returns null for a fresh account, the `coalesce(..., 0)` handles it. If `StronglyConsistent` doesn't wrap the handler in a tx, the idempotency/atomicity still holds at the repo level but confirm the cqrs transaction middleware is active (it is, via `acme-cqrs-spring-boot-starter`).
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/accounts/src/test
git commit -m "test(accounts): ledger IT — balanced posting (Σ=0), derived balance, insufficient funds, idempotency"
```

---

## Task 9: Full build + ADR

- [ ] **Step 1:** `gradle build` → BUILD SUCCESSFUL (accounts ITs on Testcontainers + acme-persistence MoneyAmount test + no regression).
- [ ] **Step 2:** Create `docs/decisions/0013-double-entry-ledger.md`:
```markdown
---
status: accepted
date: 2026-06-18
---

# Double-entry ledger (acme-bank accounts)

## Decision Outcome

- The `accounts` service is the protected hexagonal+DDD core. `Posting` is an immutable, balanced
  double-entry transaction: signed `Money` entries summing to zero within one asset (invariant in the
  domain constructor, jMolecules `@AggregateRoot`). Append-only; corrections are new postings.
- Balance is purely derived (`SUM(entries)` per account+asset) — no materialized balance, no view.
- `PostTransfer` is an `acme-cqrs` `StronglyConsistent` command: the idempotency check (by transfer id),
  source-balance fund check, and posting save commit in one DB transaction. Insufficient funds → no
  entries written; a retried transfer id applies once.
- `Money` persists via the reusable `acme-persistence` `MoneyAmount` `@Embeddable` (`NUMERIC(38,18)`
  amount + `VARCHAR` asset) — exact, lossless, vendor-portable (Postgres tested, Oracle reference).
- ArchUnit fitness functions enforce hexagonal boundaries: the domain depends on no Spring/JPA/adapter.

Full design: `docs/superpowers/specs/2026-06-18-acme-bank-design.md` §6.
```
- [ ] **Step 3:** Commit:
```bash
git add docs/decisions/0013-double-entry-ledger.md
git commit -m "docs: ADR 0013 double-entry ledger (accounts core)"
```

---

## Done criteria for BANK-1

- `gradle build` green (accounts ITs on Testcontainers, ArchUnit fitness passing).
- `accounts` core: `Posting` Σ=0 invariant, `PostTransfer` atomic + idempotent + fund-checked, balance derived.
- `Money` round-trips through JPA `NUMERIC+asset` (the `acme-persistence` `MoneyAmount` improvement).
- Domain has zero framework dependencies (ArchUnit-enforced).
- Kafka messaging adapter (consume `PostingRequested`, emit `LedgerPosted`/`PostingRejected`) deferred to BANK-2.

---

## Self-review notes

- **Spec coverage (§6):** Account/Posting/LedgerEntry ✓, Σ=0 invariant ✓ (domain + DB sum assertion), Money
  VO ✓, PostTransfer ACID/idempotent/fund-checked ✓, derived balance (no materialization) ✓, single
  currency per posting ✓ (Posting.add enforces same-asset), audit attribution — `AuditedEntity` is not
  extended here (ledger entries are append-only value rows, not audited mutable aggregates); revisit if
  needed. ArchUnit ✓. jMolecules ✓.
- **Type consistency:** `AccountId`/`Iban`/`Account`/`Posting`/`LedgerEntry`, ports `Accounts`/`Ledger`,
  `PostTransferCommand`/`PostTransferResult`/`PostTransferHandler`, `MoneyAmount.from/toMoney`,
  `Money`/`Asset`/`Assets` from BANK-0 — consistent.
- **No placeholders.** Concrete code/SQL throughout.
- **Risk:** `ddl-auto: validate` vs `MoneyAmount` embeddable column names — the embeddable's `@Column`
  names (`amount`, `asset`) match the Flyway DDL; Task 8 debug note covers mismatch. The `@Embedded`
  amount on `ledger_entry` produces `amount`+`asset` columns (no prefix) — matches the migration.
