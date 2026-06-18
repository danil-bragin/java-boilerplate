package com.acme.bank.bench;

/**
 * Central resolution of the benchmark target + load parameters from JVM system properties (with
 * localhost defaults matching the compose.bank.yaml published ports). Every simulation and the
 * setup helpers read their configuration through here so the {@code run-benchmarks.sh} orchestration
 * and the simulations stay in lockstep.
 *
 * <p>System properties (all overridable via {@code -DBENCH_*=...} on the gradle command line):
 *
 * <ul>
 *   <li>{@code BENCH_GATEWAY_URL} — the gateway edge (default {@code http://localhost:8080}).
 *   <li>{@code BENCH_KEYCLOAK_URL} — the Keycloak base (default {@code http://localhost:8082}).
 *   <li>{@code BENCH_USERS} — virtual users injected over the ramp (default 30).
 *   <li>{@code BENCH_RAMP_SECONDS} — ramp duration (default 30).
 *   <li>{@code BENCH_HOLD_SECONDS} — steady-state hold after the ramp (default 60).
 *   <li>{@code BENCH_RATE} — arrival rate for rate-based (mixed) profiles (default 50/s).
 *   <li>{@code BENCH_LEDGER_DEPTH} — pre-seeded ledger entries on read targets (default 10).
 *   <li>{@code BENCH_SOURCE_MODE} — {@code cross} (distinct sources) or {@code hot} (one shared
 *       source) for the transfer write path (default {@code cross}).
 *   <li>{@code BENCH_POOL_SIZE} — number of source/destination account pairs to pre-open (default 32).
 *   <li>{@code BENCH_TOKEN_COUNT} — Keycloak tokens to pre-fetch and round-robin (default 8).
 * </ul>
 */
public final class BenchEnv {

    private BenchEnv() {}

    public static String gatewayUrl() {
        return strip(prop("BENCH_GATEWAY_URL", "http://localhost:8080"));
    }

    public static String keycloakUrl() {
        return strip(prop("BENCH_KEYCLOAK_URL", "http://localhost:8082"));
    }

    public static int users() {
        return intProp("BENCH_USERS", 30);
    }

    public static int rampSeconds() {
        return intProp("BENCH_RAMP_SECONDS", 30);
    }

    public static int holdSeconds() {
        return intProp("BENCH_HOLD_SECONDS", 60);
    }

    public static int rate() {
        return intProp("BENCH_RATE", 50);
    }

    public static int ledgerDepth() {
        return intProp("BENCH_LEDGER_DEPTH", 10);
    }

    /** {@code cross} (distinct sources, no lock contention) or {@code hot} (one shared source). */
    public static String sourceMode() {
        return prop("BENCH_SOURCE_MODE", "cross").trim().toLowerCase();
    }

    public static boolean hotSource() {
        return "hot".equals(sourceMode());
    }

    public static int poolSize() {
        return intProp("BENCH_POOL_SIZE", 32);
    }

    public static int tokenCount() {
        return intProp("BENCH_TOKEN_COUNT", 8);
    }

    static String prop(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            // Allow env-var override too (handy from shell scripts / CI).
            v = System.getenv(key);
        }
        return (v == null || v.isBlank()) ? def : v;
    }

    static int intProp(String key, int def) {
        try {
            return Integer.parseInt(prop(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
