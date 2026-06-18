# 0026 — Saga-Settle Tuning: Partition All Saga Topics + Per-Service Concurrency + Latency Knobs

**Date:** 2026-06-18
**Status:** Accepted

## Context

ADR-0025 (BANK-15) made `posting-requested` multi-partition and source-account-keyed
so each account is a single writer, and gave accounts a per-partition consumer
concurrency. That fixed the per-account write lane but left the rest of the saga on
its original single-partition / single-thread defaults: `transfer-requested`,
`transfer-screened`, `transfer-completed`, `transfer-failed`, `ledger-posted` and
`posting-rejected` were each effectively a 1-partition funnel, and every consumer
(antifraud, transfers, notifications, the gateway projection) processed them on one
thread. So end-to-end settle latency and saga throughput were bounded by the slowest
single-threaded hop, not by the work itself.

A second, subtler defect surfaced in the BANK-15 review: declaring a topic's
`NewTopic` only on its **producer** lets a consumer that starts first auto-create the
topic at the broker default (1 partition) — a silent throughput funnel that no test
would catch until production load. BANK-15 fixed this for `posting-requested` (both
transfers and accounts declare it); it needs generalizing to every saga topic.

Standing constraints (unchanged): nothing in the money path may be weakened. Keying
preserves per-entity ordering (`transferId` for most topics; source account for
`posting-requested`); the BANK-11 source lock, the BANK-1 posting-PK anchor, and the
inbox dedup all remain; the gateway projection keeps `auto-offset-reset=earliest` so
it rebuilds the read model from the log; and producer durability — `acks=all` plus the
idempotent producer — must not be traded for latency.

## Decision

Scale the whole saga the way BANK-15 scaled one topic: **partition every saga topic,
raise per-service consumer concurrency to match, and apply latency knobs** — all keyed
to preserve per-entity ordering, with **no money-safety surface touched**.

1. **Every service provisions every saga topic it produces OR consumes.** Each service
   declares a `NewTopic` (via `TopicBuilder`) in its own `SagaTopicsConfig` for every
   saga topic it touches, at the **shared** partition count
   `${acme.bank.topics.partitions:6}` (BANK-15's per-topic `posting-requested`
   property is folded into this one — all saga topics at the same count, consistent).
   The auto-configured `KafkaAdmin` creates/grows the topic to the declared count on
   context refresh, **before** any listener subscribes. `KafkaAdmin` create-or-modify
   is idempotent, so multiple services declaring the same topic is fine.

2. **Consumers refuse auto-create.** Every consuming service sets
   `spring.kafka.consumer.properties.allow.auto.create.topics: false`, so a missing
   topic is a **loud error**, never a silent 1-partition funnel — regardless of which
   service starts first or whether a producer is up.

3. **Raise per-service consumer concurrency.** Each consuming service sets
   `spring.kafka.listener.concurrency: ${<SVC>_LISTENER_CONCURRENCY:6}` (≤ partition
   count) on the default container factory — one thread per partition. No service uses
   a custom listener container factory, so the global property takes effect for every
   `@KafkaListener` (including the gateway projection's four listeners). accounts was
   already set in BANK-15.

4. **Latency knobs.** Producer `linger.ms = ${KAFKA_LINGER_MS:5}` (a few-ms batching
   window favors settle latency); consumer `fetch.max.wait.ms =
   ${KAFKA_FETCH_MAX_WAIT_MS:50}` (shorter poll wait under light load) and
   `max.poll.records = ${KAFKA_MAX_POLL_RECORDS:50}` (bounded batch). These are pure
   latency/throughput knobs and change no delivery, ordering, or money guarantee.

### Why this is correct (and why durability is untouched)

- **Per-entity ordering holds.** Keying is unchanged. A given key always hashes to one
  partition, which is consumed by one thread, so a key's events stay strictly ordered
  and the inbox dedup / posting anchor / source lock guarantees are unaffected.
  Partitions add parallelism **across** keys, never within a key.
- **Concurrency ≤ partitions.** One thread per partition; extra threads beyond the
  partition count simply idle (harmless). On a single-partition test topic concurrency
  caps at 1, so the ITs are unaffected.
- **Durability is NON-NEGOTIABLE and now load-bearing in config.** `acks=all` and
  `enable.idempotence=true` are the Kafka 3.x client defaults, but relying on a default
  for a money guarantee is fragile — a future override could silently weaken it. So
  every saga producer (transfers, accounts, antifraud) now sets **both explicitly**.
  `linger.ms` only batches *when* a send is dispatched; it does not relax acks or
  idempotence. We did NOT lower `acks` for latency.
- **No money-path change.** Keying, `auto-offset-reset` (gateway stays `earliest`), the
  inbox dedup, the BANK-1 posting anchor, and the BANK-11 source lock are all
  untouched. This phase is pure parallelism + latency config.

### Proof / tests

- `PostingRequestedTopicIT` (transfers) now asserts **all seven** saga topics transfers
  touches are provisioned at 6 partitions; new `SagaTopicsIT`s in antifraud,
  notifications and gateway assert their declared topics at 6 partitions; accounts'
  `ConcurrentPostingConsumerIT` already exercises a real multi-partition topic.
- The saga ITs (`TransferAdvanceIT`, `PostingFlowIT`, `ScreeningIT`, `NotificationIT`,
  gateway `TransferProjectionIT`) stay green under `allow.auto.create.topics=false` —
  each test context's `NewTopic` beans provision the topics on the Testcontainers
  Redpanda before listeners subscribe, so no IT needed a pre-created topic or a
  re-enabled auto-create.
- The money-safety gates stay green: `ConcurrentDebitIT`, `PostTransferIT`, the saga
  ITs and the reconciler IT (`SagaReconcilerIT`) all pass; `gradle build` is green.

## Alternatives considered

- **Fewer partitions (keep some topics single-partition).** Rejected: it leaves a
  per-topic funnel and makes the saga's throughput ceiling uneven and surprising. One
  shared count (6) is consistent and easy to reason about; tune to the workload.
- **Lower `acks` (e.g. `acks=1`) for latency.** Rejected outright — for money,
  durability outranks latency. A `linger.ms` batching window already buys latency
  headroom without touching delivery guarantees.
- **Declaring each topic only on its producer.** Rejected: the BANK-15 review showed a
  consumer that starts first then auto-creates the topic at 1 partition — a silent
  funnel. Declaring on every service that touches the topic + `allow.auto.create=false`
  makes provisioning deterministic and a missing topic loud.
- **More partitions than 6.** Orthogonal — tune via `acme.bank.topics.partitions`.
  Consumer concurrency must stay ≤ partition count.

## Consequences

- **Settle latency is reduced and saga throughput rises** without trading any
  delivery, ordering, or money guarantee: partitioning + per-service concurrency
  parallelize the saga **across** transfers/accounts, and the latency knobs shave the
  per-hop wait.
- **6 is a starting point, not a tuned value.** The partition count and the latency
  knobs are env-overridable (`TOPIC_PARTITIONS`, `KAFKA_LINGER_MS`,
  `KAFKA_FETCH_MAX_WAIT_MS`, `KAFKA_MAX_POLL_RECORDS`, `<SVC>_LISTENER_CONCURRENCY`);
  tune to the workload. Consumer concurrency must stay ≤ partition count.
- **Operational note:** growing partitions on an *existing* keyed topic remaps keys, so
  the partition count is fixed at first create. The example deploys fresh
  (`docker compose down -v`); production sets the partition count and a real
  replication factor at the broker / topic-provisioning layer (dev/Redpanda is
  single-broker, replicas = 1).
- **The quantified delta comes in BANK-17.** This ADR establishes the mechanism; the
  benchmark will measure the latency/throughput improvement.
- All money-safety guarantees from BANK-0..15 stand unchanged: keying/ordering, the
  inbox dedup, the BANK-1 posting anchor, the BANK-11 source lock, `acks=all` + the
  idempotent producer (now explicit). `ConcurrentDebitIT` stays green as the regression
  gate.
