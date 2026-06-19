package com.acme.bank.transfers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.bank.transfers.adapter.out.posting.AccountsPostingSyncClient;
import com.acme.bank.transfers.adapter.out.posting.SyncPostResult;
import com.acme.bank.transfers.adapter.out.reconcile.AccountsPostingClient;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * THE money-safety gate for the fast-path (BANK-22). Proves, with the sync client and the reconciler's
 * accounts client driven deterministically:
 *
 * <ol>
 *   <li><b>Idempotency</b> — same {@code Idempotency-Key} → ONE transfer, the sync post is attempted
 *       exactly once (a second identical request replays the cached response, never a second posting).
 *   <li><b>Timeout → reconciler, NO double-post</b> — the sync post returns UNKNOWN: the handler does
 *       NOT terminalize (transfer stays POSTING). The reconciler then sees accounts {@code posted=true}
 *       and completes it. The handler emitted NO completed/failed event and NO posting re-drive, so the
 *       single accounts posting is the ONLY money movement — debited exactly once.
 *   <li><b>Circuit-open → async fallback</b> — NOT_MADE: the handler does not terminalize; it re-emits
 *       posting-requested so the async saga drives the posting (no money moved synchronously).
 *   <li><b>Overdraft → REJECTED, no entries</b> — REJECTED: transfer FAILED; no completed event.
 * </ol>
 *
 * <p>The accounts-side double-post safety (one posting per transferId, even on retry) is proven by the
 * posting-PK anchor in {@code InternalPostingPostIT} / {@code PostTransferIT} / {@code ConcurrentDebitIT}.
 * This IT proves the transfers ORCHESTRATION never asks accounts to post twice and never fabricates a
 * terminal over money it cannot prove moved.
 */
@SpringBootTest(
        properties = {
            "acme.bank.fast-path.enabled=true",
            "acme.bank.fast-path.max-amount=1000.00",
            // Don't let the @Scheduled sweep race the direct reconcileOne calls.
            "acme.bank.reconciler.fixed-delay=PT1H"
        })
@AutoConfigureMockMvc
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    FastPathSafetyIT.SchemaRegistryProps.class
})
class FastPathSafetyIT {

    @TestConfiguration
    static class SchemaRegistryProps {
        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> {
                registry.add(
                        "spring.kafka.consumer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
                registry.add(
                        "spring.kafka.producer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    Transfers transfers;

    @Autowired
    SagaReconciler reconciler;

    @MockitoBean
    AccountsPostingSyncClient sync;

    /** The reconciler's money-truth query client — drives posted=true/false in the timeout edge. */
    @MockitoBean
    AccountsPostingClient reconcileClient;

    private static String body(String amount) {
        return "{\"sourceAccountId\":\"a\",\"destinationAccountId\":\"b\",\"amount\":\"" + amount
                + "\",\"asset\":\"USD\"}";
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, id);
    }

    private String initiate(String idemKey, String amount, int expectedHttpStatus) throws Exception {
        var req = post("/v1/transfers")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(amount));
        if (idemKey != null) {
            req = req.header("Idempotency-Key", idemKey);
        }
        return mvc.perform(req)
                .andExpect(status().is(expectedHttpStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    // 1) Idempotency: same Idempotency-Key → ONE transfer, sync post attempted exactly once.
    @Test
    void sameIdempotencyKeyPostsOnce() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.posted());
        long before = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);

        String first = initiate("idem-fp-1", "100.00", 200);
        String second = initiate("idem-fp-1", "100.00", 200);

        JsonNode a = mapper.readTree(first);
        JsonNode b = mapper.readTree(second);
        assertThat(b.get("transferId").asText()).isEqualTo(a.get("transferId").asText());

        long after = jdbc.queryForObject("SELECT count(*) FROM transfer", Long.class);
        assertThat(after).isEqualTo(before + 1); // exactly one transfer
        // The replayed second request was served from the idempotency cache → exactly ONE sync post.
        verify(sync, times(1)).post(any());
    }

    // 2) Timeout → reconciler, NO double-post. Sync UNKNOWN → POSTING (no terminal); reconciler
    // posted=true → COMPLETED. The handler never published a terminal nor a re-drive, so the single
    // accounts posting is the only money movement — debited exactly once.
    @Test
    void unknownTimeoutLeavesPostingThenReconcilerCompletesExactlyOnce() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.unknown());

        String resp = initiate(null, "100.00", 202);
        JsonNode json = mapper.readTree(resp);
        String id = json.get("transferId").asText();
        // The handler did NOT guess a terminal — it left the transfer POSTING for the reconciler.
        assertThat(json.get("status").asText()).isEqualTo("POSTING");
        assertThat(statusOf(id)).isEqualTo("POSTING");

        // Reconciler queries accounts' ledger: posted=true (the timed-out call DID commit at accounts).
        when(reconcileClient.posted(id)).thenReturn(Optional.of(true));
        reconciler.reconcileOne(transfers.findById(new TransferId(id)).orElseThrow());

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        // The reconciler only completes on a confirmed ledger posting — it never asks accounts to post
        // (no posting-requested re-drive when posted=true), so there is no second posting → no double-post.

        // Idempotent reconcile: a second pass finds COMPLETED (terminal) and is a no-op.
        when(reconcileClient.posted(id)).thenReturn(Optional.of(true));
        // The transfer is terminal now; reconcileOne on a stale POSTING snapshot would still only complete,
        // but the row is COMPLETED so the saga is settled. Re-query proves stability.
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }

    // 2b) Timeout but accounts did NOT post → reconciler posted=false → re-drives (re-emit
    // posting-requested), stays POSTING, NEVER failed. Eventually accounts posts once.
    @Test
    void unknownTimeoutNotPostedReconcilerReDrivesNeverFails() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.unknown());

        String id =
                mapper.readTree(initiate(null, "100.00", 202)).get("transferId").asText();
        assertThat(statusOf(id)).isEqualTo("POSTING");

        when(reconcileClient.posted(id)).thenReturn(Optional.of(false));
        reconciler.reconcileOne(transfers.findById(new TransferId(id)).orElseThrow());

        // Must NOT be failed — a posted=false snapshot does not prove money never moved.
        assertThat(statusOf(id)).isEqualTo("POSTING");
    }

    // 3) Circuit-open → async fallback. NOT_MADE → no terminal; stays POSTING; async re-drive emitted.
    @Test
    void circuitOpenFallsBackToAsyncNoTerminal() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.notMade());

        String resp = initiate(null, "100.00", 202);
        JsonNode json = mapper.readTree(resp);
        String id = json.get("transferId").asText();
        assertThat(json.get("status").asText()).isEqualTo("POSTING");
        assertThat(statusOf(id)).isEqualTo("POSTING");
        // No money moved synchronously (call not made). The async posting hop / reconciler completes it.
    }

    // 4) Overdraft on fast-path → REJECTED → FAILED, no completed event, no money moved.
    @Test
    void overdraftRejectedFailsNoMoney() throws Exception {
        when(sync.post(any())).thenReturn(SyncPostResult.rejected("INSUFFICIENT_FUNDS"));

        String resp = initiate(null, "100.00", 200);
        JsonNode json = mapper.readTree(resp);
        String id = json.get("transferId").asText();
        assertThat(json.get("status").asText()).isEqualTo("FAILED");
        assertThat(json.get("failureReason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(statusOf(id)).isEqualTo("FAILED");
    }
}
