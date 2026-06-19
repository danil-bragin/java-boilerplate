package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient;
import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient.SyncPostRequest;
import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClientConfig.AccountsSyncClientProperties;
import com.acme.bank.transfers.adapter.out.posting.SyncPostResult;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * BANK-22 Fix 3 — the in-tx sync call cannot pin connections: tight timeouts + max-attempts 1 bound the
 * connection-hold, and the circuit breaker trips FAST on a down accounts (→ NOT_MADE → async fallback)
 * instead of holding a Hikari connection per request. This IT points the sync client at a black-hole
 * port (connection refused) and proves:
 *
 * <ul>
 *   <li>the configured timeouts are TIGHT (connect ≤ 200ms, read ≤ 500ms) — the worst-case hold is small;
 *   <li>after the sliding window fills with failures the breaker OPENS → subsequent calls short-circuit to
 *       {@link SyncPostResult.Outcome#NOT_MADE} (CallNotPermittedException) WITHOUT dispatching — no
 *       connection is held on a down accounts.
 * </ul>
 */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            // Black-hole: a port with nothing listening → fast connection-refused on every dispatch.
            "acme.bank.fast-path.accounts.base-url=http://localhost:59999",
            // The test application.yaml shadows the main one (same classpath path), so the production
            // resilience4j config isn't loaded under test — restate the accounts-sync breaker/retry here so
            // this IT exercises the REAL Fix 3 settings (min-calls 10 → breaker can open within the window).
            "resilience4j.circuitbreaker.instances.accounts-sync.sliding-window-size=10",
            "resilience4j.circuitbreaker.instances.accounts-sync.minimum-number-of-calls=10",
            "resilience4j.circuitbreaker.instances.accounts-sync.failure-rate-threshold=50",
            "resilience4j.circuitbreaker.instances.accounts-sync.wait-duration-in-open-state=5s",
            "resilience4j.retry.instances.accounts-sync.max-attempts=1"
        })
@Import(PostgresTestcontainersConfiguration.class)
class AccountsSyncTimeoutIT {

    @Autowired
    AccountsPostingSyncClient syncClient;

    @Autowired
    AccountsSyncClientProperties props;

    @Autowired
    CircuitBreakerRegistry breakerRegistry;

    @Test
    void timeoutsAreTight() {
        assertThat(props.getConnectTimeout()).isLessThanOrEqualTo(Duration.ofMillis(200));
        assertThat(props.getReadTimeout()).isLessThanOrEqualTo(Duration.ofMillis(500));
    }

    @Test
    void downAccountsTripsBreakerToNotMadeWithoutPinning() {
        SyncPostRequest req = new SyncPostRequest("t-down", "src", "dst", Money.of("100.00", Assets.USD));

        // Drive enough calls to fill the 10-wide sliding window past the 50% failure threshold. Each call
        // is a fast connection-refused (ResourceAccessException → UNKNOWN) until the breaker opens, after
        // which calls short-circuit to NOT_MADE (CallNotPermittedException) — never dispatched, no hold.
        boolean sawNotMade = false;
        for (int i = 0; i < 30 && !sawNotMade; i++) {
            SyncPostResult result = syncClient.post(req);
            if (result.outcome() == SyncPostResult.Outcome.NOT_MADE) {
                sawNotMade = true;
            }
        }

        CircuitBreaker cb = breakerRegistry.circuitBreaker("accounts-sync");
        assertThat(sawNotMade)
                .as("breaker must open and short-circuit to NOT_MADE (async fallback) on a down accounts; "
                        + "final state=" + cb.getState())
                .isTrue();
        // The breaker is OPEN — subsequent calls won't even dispatch (no connection held on a down accounts).
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
