#!/usr/bin/env bash
#
# BANK-13 load-benchmark orchestration. Brings up the full compose.bank.yaml stack, waits for the
# gateway edge, runs each Gatling simulation against the live system with the chosen load parameters,
# captures per-container resource stats during steady state, then tears the stack down.
#
# Usage:
#   examples/acme-bank/benchmarks/run-benchmarks.sh up        # build jars + start stack + wait ready
#   examples/acme-bank/benchmarks/run-benchmarks.sh run <SimulationClass> [extra -D props...]
#   examples/acme-bank/benchmarks/run-benchmarks.sh stats     # snapshot docker stats once
#   examples/acme-bank/benchmarks/run-benchmarks.sh down      # tear down (-v)
#   examples/acme-bank/benchmarks/run-benchmarks.sh all       # up, run the full suite, down
#
# Load params are passed through as -DBENCH_* (see BenchEnv): BENCH_USERS, BENCH_RAMP_SECONDS,
# BENCH_HOLD_SECONDS, BENCH_RATE, BENCH_LEDGER_DEPTH, BENCH_SOURCE_MODE, BENCH_POOL_SIZE.
#
# NOTE: everything is co-located on one dev host. Keep the load MODEST and COMPARATIVE — the durable
# deliverable is the RELATIVE bottleneck ranking, not absolute RPS. If the host destabilizes
# (containers OOM/restart), BACK OFF and record the limiter; do not keep hammering.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE="$ROOT/examples/acme-bank/compose.bank.yaml"
# BANK-18: the canonical bench override lifts ALL FOUR protective artifacts (rate-limit, breaker
# slow-call/failure thresholds, retry amplification) via env (prod defaults in compose.bank.yaml /
# application.yaml unchanged — `docker compose -f compose.bank.yaml config` still shows 100/min +
# breaker 50/60s + retry 3). The older benchmarks/compose.bench-override.yaml (rate-limit only) is
# superseded by this file.
OVERRIDE="$ROOT/examples/acme-bank/compose.bench.yaml"
GATEWAY="${BENCH_GATEWAY_URL:-http://localhost:8080}"
PKG="com.acme.bank.bench"

up() {
  echo ">> building bank jars"
  (cd "$ROOT" && gradle bankJars)
  echo ">> docker compose up --build --wait (with bench rate-limit override)"
  docker compose -f "$COMPOSE" -f "$OVERRIDE" up -d --build --wait
  echo ">> waiting for gateway readiness at $GATEWAY"
  for i in $(seq 1 60); do
    if curl -sf "$GATEWAY/actuator/health/readiness" | grep -q UP; then
      echo ">> gateway ready"
      return 0
    fi
    sleep 2
  done
  echo "!! gateway did not become ready" >&2
  return 1
}

run() {
  local sim="$1"; shift || true
  echo ">> running $PKG.$sim $*"
  (cd "$ROOT" && gradle :examples:acme-bank:benchmarks:gatlingRun \
      --simulation "$PKG.$sim" "$@")
}

stats() {
  echo ">> docker stats snapshot @ $(date -u +%FT%TZ)"
  docker stats --no-stream \
    --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}'
}

down() {
  echo ">> docker compose down -v"
  docker compose -f "$COMPOSE" -f "$OVERRIDE" down -v
}

# Reset the gateway resilience4j circuit breakers (which, once opened under burst load, stay open —
# the @CircuitBreaker fallback methods are private and unreachable, so half-open probes never recover).
# Call between aggressive runs.
reset_breaker() {
  echo ">> restarting gateway to reset circuit breakers"
  docker compose -f "$COMPOSE" -f "$OVERRIDE" restart gateway
  for i in $(seq 1 30); do
    curl -sf "$GATEWAY/actuator/health/readiness" 2>/dev/null | grep -q UP && return 0
    sleep 2
  done
}

# BANK-18: read the saga consumer-group lag — the honest "writes/s the system holds" is the rate at
# which this stays BOUNDED. Unbounded growth at the accept rate means the accept rate is NOT sustainable.
lag() {
  for g in accounts transfers gateway-projection; do
    echo ">> rpk group describe $g @ $(date -u +%FT%TZ)"
    docker compose -f "$COMPOSE" -f "$OVERRIDE" exec -T redpanda rpk group describe "$g" 2>/dev/null \
      | grep -E "TOPIC|LAG|^$g|PARTITION" || true
  done
}

case "${1:-all}" in
  up) up ;;
  run) shift; run "$@" ;;
  stats) stats ;;
  lag) lag ;;
  down) down ;;
  reset) reset_breaker ;;
  capacity)
    # BANK-18 capacity sweep: open-model ramp-to-knee per path. Stack must already be up WITH the
    # bench override. Watch `run-benchmarks.sh lag` during the write run to find the lag-bounded
    # sustainable saga rate vs the peak POST-accept rate.
    run CapacitySweepSimulation -DBENCH_CAPACITY_MODE=write -DBENCH_PEAK_RPS="${BENCH_PEAK_RPS:-200}" \
        -DBENCH_RAMP_SECONDS="${BENCH_RAMP_SECONDS:-30}" -DBENCH_HOLD_SECONDS="${BENCH_HOLD_SECONDS:-30}" \
        -DBENCH_POOL_SIZE="${BENCH_POOL_SIZE:-64}"
    run CapacitySweepSimulation -DBENCH_CAPACITY_MODE=read -DBENCH_PEAK_RPS="${BENCH_PEAK_RPS:-600}" \
        -DBENCH_RAMP_SECONDS="${BENCH_RAMP_SECONDS:-30}" -DBENCH_HOLD_SECONDS="${BENCH_HOLD_SECONDS:-30}"
    run CapacitySweepSimulation -DBENCH_CAPACITY_MODE=mixed -DBENCH_PEAK_RPS="${BENCH_PEAK_RPS:-300}" \
        -DBENCH_RAMP_SECONDS="${BENCH_RAMP_SECONDS:-30}" -DBENCH_HOLD_SECONDS="${BENCH_HOLD_SECONDS:-30}" \
        -DBENCH_POOL_SIZE="${BENCH_POOL_SIZE:-64}"
    ;;
  all)
    # The write path saturates well below the read path on this co-located host: keep transfer load
    # MODEST (open-model ~3 req/s) and reset the breaker between runs. Reads tolerate high concurrency.
    up
    run TransferWriteSimulation -DBENCH_SOURCE_MODE=cross -DBENCH_ARRIVAL_RATE=3 -DBENCH_RAMP_SECONDS=5 -DBENCH_HOLD_SECONDS=30
    reset_breaker
    run TransferWriteSimulation -DBENCH_SOURCE_MODE=hot -DBENCH_ARRIVAL_RATE=3 -DBENCH_RAMP_SECONDS=5 -DBENCH_HOLD_SECONDS=30
    reset_breaker
    run ReadPathSimulation -DBENCH_LEDGER_DEPTH=10 -DBENCH_USERS=4
    run SagaSettleSimulation -DBENCH_RATE=10 -DBENCH_POOL_SIZE=12
    reset_breaker
    # Deep-ledger read targets must be pre-seeded directly in Postgres (see BENCHMARKS.md "Deep-ledger
    # seeding"); pass the account id via -DBENCH_READ_TARGET to skip the breaker-tripping saga seed.
    stats
    down
    ;;
  *) echo "usage: $0 {up|run <Sim>|stats|lag|reset|down|capacity|all}" >&2; exit 2 ;;
esac
