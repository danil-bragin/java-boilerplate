# BANK-17: scaling before/after benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Quantify the BANK-15/16 scaling work by re-running the BANK-13 Gatling suite against the tuned stack, comparing to the BANK-13 baseline (the "before" numbers already in `BENCHMARKS.md`), and measuring the cross-account saga-settle parallelism gain — then update `BENCHMARKS.md` with an honest before/after delta and the single-host caveat.

**Architecture:** Reuse the BANK-13 `examples/acme-bank/benchmarks` Gatling harness + `run-benchmarks.sh`. Run the same scenarios (write cross/hot, saga-settle, mixed, read) at the same load levels on the BANK-15/16 stack. Add one targeted measurement: saga-settle throughput/latency at the new partition count + concurrency vs the BANK-13 single-partition baseline. Honesty: on a single co-located host the per-account write change won't show a large full-stack delta (Postgres CPU caps first); the settle change (partitions × concurrency) should show a measurable delta. Report what actually moved.

**Tech Stack:** Gatling (existing harness), the BANK-9 compose stack at the BANK-15/16 config, otel-lgtm/Prometheus + `docker stats` for resource capture.

> Spec: `docs/superpowers/specs/2026-06-18-acme-bank-scaling-design.md` §3 (BANK-17) + §1 honest caveat. Builds on BANK-13/15/16.
> **Environment (every task):** work from `/Users/npden4ik/Projects/java-boilerplate`, on `master`; system `gradle` (8.14) NEVER `./gradlew`; JDK 21; Docker up; all images cached. A live load run is resource-heavy (5 service JVMs + infra + Gatling) — keep load MODEST and COMPARATIVE (same levels as BANK-13 so the comparison is apples-to-apples). If the host caps the run, record what was achieved + the limiter; do NOT fabricate. `gradle <module>:spotlessApply` before commits.

---

## Task 1: re-run the BANK-13 suite on the tuned stack (same load levels)

**Files:** reuse `examples/acme-bank/benchmarks/*` + `run-benchmarks.sh`; capture results.

- [ ] **Step 1:** Read `examples/acme-bank/BENCHMARKS.md` for the BANK-13 baseline numbers (the "before"): per-scenario RPS, p50/p95/p99, error%, and the recorded load levels. These are the comparison baseline.
- [ ] **Step 2:** Bring up the BANK-15/16 stack: `gradle bankJars && docker compose -f examples/acme-bank/compose.bank.yaml up -d --build --wait`. Confirm `posting-requested` (and the other saga topics) have 6 partitions: `docker compose ... exec redpanda rpk topic describe posting-requested` (or via the admin) — verify the BANK-15/16 provisioning actually took effect in the deployed stack (NOT 1 partition). Record the partition counts.
- [ ] **Step 3:** Re-run each simulation at the SAME load levels BANK-13 used (`run-benchmarks.sh` with the same `BENCH_USERS`/ramp/hold): write cross-account, write hot-account, saga-settle, read, mixed. Capture per-scenario RPS, p50/p95/p99, error%, and steady-state resource signals (`docker stats` per service, Hikari active, redpanda consumer lag, per-service CPU).
- [ ] **Step 4:** Tear down (`docker compose ... down -v`).
- [ ] **Step 5:** Save the distilled after-numbers (no commit yet — Task 3 writes them into the report).

---

## Task 2: targeted saga-settle parallelism measurement

**Files:** reuse `SagaSettleSimulation` (or a focused variant); capture.

- [ ] **Step 1:** Measure the saga-settle path specifically — the change most likely to show a delta (partitions × consumer concurrency). Run `SagaSettleSimulation` (POST→COMPLETED wall-clock) at an increasing arrival rate (`constantUsersPerSec`) on the tuned stack and find where settle p95/p99 knees. Compare to the BANK-13 baseline settle numbers (single-partition, concurrency 1).
- [ ] **Step 2:** If feasible, capture the redpanda consumer-group lag during the settle run at the old vs new partition/concurrency to show the parallelism (more partitions consumed in parallel → lower lag at the same arrival rate). Note the partition count and per-service `listener.concurrency` in effect.
- [ ] **Step 3:** Record the settle before/after (latency distribution + max sustained arrival rate before the knee).

---

## Task 3: update BENCHMARKS.md (before/after) + ADR

**Files:** `examples/acme-bank/BENCHMARKS.md` (add a "Scaling: before/after (BANK-15/16)" section), `docs/decisions/0027-scaling-benchmark.md`.

- [ ] **Step 1:** Add a **before/after** section to `BENCHMARKS.md`:
  - A table per scenario: BANK-13 baseline vs BANK-15/16 (RPS, p50/p95/p99, error%, limiting resource).
  - The saga-settle delta (latency distribution + max arrival rate) — the headline expected gain from partitions × concurrency.
  - The per-account write change: state honestly whether a full-stack delta was observable on this host (likely NOT — Postgres CPU / co-location caps before the lock) and that the benefit is a raised per-account ceiling + removed lock-wait overhead, provable at multi-host scale; cite the BANK-15 `ConcurrentDebitIT`/`ConcurrentPostingConsumerIT` as the correctness+single-writer evidence.
  - Confirm the topics are genuinely 6-partition in the deployed stack (Task 1 Step 2).
  - Keep the standing caveat: co-located single host → relative not absolute.
- [ ] **Step 2:** ADR `docs/decisions/0027-scaling-benchmark.md` — the measured outcome of BANK-15/16: what moved (settle), what didn't on this host (per-account, with the honest reason), the resource that now caps each path, and the next lever beyond the application layer (Postgres read replicas / separate instances / more brokers). Link BENCHMARKS.md.
- [ ] **Step 3:** `gradle build` → SUCCESSFUL (benchmarks excluded from build).
- [ ] **Step 4: commit**
```bash
git add examples/acme-bank/BENCHMARKS.md docs/decisions/0027-scaling-benchmark.md examples/acme-bank/benchmarks
git commit -m "docs(benchmarks): BANK-15/16 before/after scaling results + ADR 0027 (settle delta; honest per-account single-host caveat)"
```

---

## Done criteria for BANK-17

- The BANK-13 suite re-run on the BANK-15/16 tuned stack at the same load; topics confirmed 6-partition in the deployed stack.
- A before/after comparison in `BENCHMARKS.md`: the saga-settle delta quantified; the per-account write change honestly assessed (ceiling raised + lock-wait removed; full-stack delta limited on a single host, with the reason).
- ADR 0027 records the measured outcome + the next (infra) lever.
- `gradle build` green.

---

## Self-review notes

- **Spec coverage:** §3 BANK-17 before/after benchmark (T1,T2), honest report (T3) ✓.
- **Consistency:** reuses BANK-13 `benchmarks` harness + scenarios + load levels (apples-to-apples); reads the BANK-13 baseline from `BENCHMARKS.md`.
- **No placeholders:** concrete scenarios, the partition-count verification, the before/after tables.
- **Honesty (the core requirement):** do NOT claim a per-account full-stack speedup the single host can't show. Report the settle delta where it's real, and frame the per-account change as a ceiling-raiser + correctness win (already proven by BANK-15 tests), not a measured single-host RPS jump. If the host caps a run, record the achieved numbers + the limiter.
- **Risk:** running the full stack + Gatling is heavy; if it destabilizes the host, back off and record partial results + limiter (do not hammer). Verify the deployed topics are actually 6 partitions (the whole BANK-15/16 premise) before trusting the settle numbers.
