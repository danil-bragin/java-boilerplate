# 0021 — End-to-End Test Strategy: ComposeContainer + a Real Keycloak Token

**Date:** 2026-06-18
**Status:** Accepted

## Context

BANK-0..9 verified the system one hop at a time — each saga step has an Avro IT against real Redpanda
+ Postgres (Testcontainers) — and BANK-9 made the whole thing deployable via `compose.bank.yaml`. What
was still missing is a **black-box test of the entire system through its public edge**: nobody asserted
that a client with a real token can `POST /v1/transfers` and watch the choreographed saga
(requested → screened → posting → ledger-posted → completed) settle to **COMPLETED** with money
actually moved — through the gateway, across Kafka, into accounts and back into the gateway's read
model. Per-hop ITs can all pass while the assembled system is misconfigured (a wrong issuer URL, a
missing cache, a broken projection). BANK-10 adds that end-to-end proof, plus a thin functional-contract
pass over the per-service API ITs.

## Decision

### `ComposeContainer(compose.bank.yaml)` over a hand-rolled network

The e2e module (`examples/acme-bank/e2e`, test-only, no `main`) brings up the stack with Testcontainers
`ComposeContainer` pointed at the **same** `compose.bank.yaml` that ships, using
`withLocalCompose(true)` and `withBuild(true)` (it builds the five service images from the BANK-9
Dockerfiles). The point is to **test exactly what is deployed** — the real compose wiring, healthchecks,
env, issuer config and all — not a parallel Testcontainers network that could drift from production.
The `e2eTest` Gradle task `dependsOn` each service's `bootJar` so the jars the Dockerfiles `COPY` exist
before the image build.

### A real Keycloak token via password grant (not a mocked decoder)

The e2e fetches a genuine access token from the running Keycloak container via the OAuth2 password
(direct-access) grant on the public `bank-gateway` client (`KeycloakToken.fetch`). The **whole auth
path is exercised**: OIDC discovery, JWKS, signature + issuer validation in every service. The per-hop
ITs use `jwt()` post-processors / a mocked decoder; only the e2e proves the real token is accepted edge
to edge — which is the single most error-prone part of a Keycloak-behind-a-gateway setup.

### Issuer consistency: pin a constant full-URL issuer

This is the crux. Every service validates `iss = http://keycloak:8080/realms/bank` (the in-network
URL). BANK-9 used `KC_HOSTNAME=keycloak` (a bare hostname), under which a token fetched from the **host**
via `localhost:8082` carried `iss=http://localhost:8082/...` and was rejected (401) — so host-side e2e
was impossible without network tricks. BANK-10 changes the compose Keycloak to `KC_HOSTNAME` =
**`http://keycloak:8080`** (a full URL). In Keycloak's hostname-v2, a full-URL hostname pins the
frontend **and** backchannel URLs to that constant, so **every** minted token carries
`iss=http://keycloak:8080/realms/bank` regardless of the caller's host. The e2e (on the host) fetches
the token through the mapped port and the services accept it. This supersedes ADR-0020's "fetch tokens
in-network only" caveat with a more robust constant-issuer rule.

### Tagged `e2e` and excluded from the default build

The e2e is `@Tag("e2e")`. The e2e module's `test` task (run by `check`/`build`) `excludeTags("e2e")`,
and a dedicated `e2eTest` task `includeTags("e2e")`. So **`gradle build` stays green and fast** —
only the e2e module's `compileTestJava` runs in the default build, which still proves the e2e wiring
compiles on every PR; the heavy full run (image pulls of Keycloak + otel-lgtm + image builds) is the
on-demand entrypoint `gradle :examples:acme-bank:e2e:e2eTest`, wired into a nightly / `workflow_dispatch`
CI job. One stack is shared per test class (`@TestInstance(PER_CLASS)`) to bound the (large) startup cost.

### Scenarios and what each proves end-to-end

- **happy-path** — two accounts opened, a transfer requested → settles to **COMPLETED**; balances
  moved; the destination **statement** shows the credit. Proves the full choreography + read-model.
- **rejected** — an amount above the antifraud `AMOUNT_LIMIT` (10000 USD) → **FAILED**, reason
  surfaced, **no money moved**. Proves the antifraud hop and the compensation/no-posting path.
- **idempotency** — the same `Idempotency-Key` + body → one transfer, source debited **once**. Proves
  the gateway's shared (Redis) idempotency under the real edge.
- **auth-negative** — no bearer / a garbage bearer → **401**. Proves the resource-server rejects at the
  edge before any business logic.

## Consequences

- The e2e tests the shipped artifact (compose + images + realm + issuer), so a misassembled stack fails
  *here* rather than in production. It already surfaced the value of the constant-issuer rule.
- The default `gradle build` is unaffected by the e2e's cost; the wiring is still compile-checked on
  every PR. The full e2e needs a Docker daemon and tolerable image-pull time, so it lives off the fast
  path (nightly / on-demand).
- A constrained environment (no cached Keycloak/otel-lgtm images, or a stack-level boot failure) can
  prevent a *live* run; the module + tests still **compile and are correctly wired**, and run wherever
  the images are available and the stack boots cleanly.

## Alternatives Considered

- **In-JVM multi-context `@SpringBootTest`** (boot all services in one JVM) — rejected: it wouldn't
  exercise the real images, the real network, Keycloak, or the compose healthchecks/issuer config; it
  tests an assembly that never ships.
- **A hand-rolled Testcontainers `Network` wiring the built images** — rejected in favor of
  `ComposeContainer(compose.bank.yaml)`: reusing the shipped compose file avoids drift between "what the
  e2e wires" and "what is deployed."
- **A mocked JWT decoder / `jwt()` post-processor at the edge** — already covered by the per-hop ITs;
  the e2e deliberately uses a real Keycloak token so the discovery/JWKS/issuer path is proven.
- **Kubernetes-based e2e (kind/k3s + Helm)** — deferred; compose is enough to prove the shape. A k8s e2e
  is a natural follow-up alongside a Helm chart.
