package com.acme.bank.transfers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.acme.bank.contracts.avro.PostingRequested;
import com.acme.bank.contracts.avro.TransferCompleted;
import com.acme.bank.contracts.avro.TransferFailed;
import com.acme.bank.contracts.avro.TransferRequested;
import com.acme.bank.transfers.domain.Transfer;
import com.acme.bank.transfers.domain.TransferId;
import com.acme.bank.transfers.domain.Transfers;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Money-safety contract of the saga reconciler. {@link com.acme.bank.transfers.adapter.out.reconcile.AccountsPostingClient}
 * is mocked to drive the three ledger answers (posted=true / false / transport-error). Each case
 * invokes {@code reconcileOne(Transfer)} directly (no waiting on the schedule).
 */
@SpringBootTest(
        properties = {
            "spring.autoconfigure.exclude=com.acme.security.autoconfigure.SecurityAutoConfiguration",
            // Don't let the @Scheduled sweep race the direct reconcileOne calls.
            "acme.bank.reconciler.fixed-delay=PT1H"
        })
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    SagaReconcilerIT.SchemaRegistryProps.class
})
class SagaReconcilerIT {

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

        // A real registry so the reconciler's acme.saga.stuck counter is observable in the test.
        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }

    @Autowired
    RedpandaContainer redpanda;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Transfers transfers;

    @Autowired
    SagaReconciler reconciler;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @MockitoBean
    com.acme.bank.transfers.adapter.out.reconcile.AccountsPostingClient accountsPostingClient;

    /** Seed a transfer in {@code status} whose updated_at is {@code ageSeconds} in the past. */
    private void seed(String id, String status, long ageSeconds) {
        jdbc.update(
                "INSERT INTO transfer(id, source_account_id, destination_account_id, amount, asset, requested_by, status, updated_at) "
                        + "VALUES (?, 'acc-src', 'acc-dst', 100, 'USD', 'alice', ?, ?) ON CONFLICT (id) DO NOTHING",
                id,
                status,
                java.sql.Timestamp.from(Instant.now().minusSeconds(ageSeconds)));
    }

    private Transfer load(String id) {
        return transfers.findById(new TransferId(id)).orElseThrow();
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, id);
    }

    // (a) POSTING + accounts says posted -> COMPLETED + emits transfer-completed (recovers lost ledger-posted)
    @Test
    void postingPostedTrueCompletes() {
        String id = "rec-a-" + System.nanoTime();
        seed(id, "POSTING", 60); // > nudge (30s), < fail (5m)
        when(accountsPostingClient.posted(id)).thenReturn(Optional.of(true));

        reconciler.reconcileOne(load(id));

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        awaitRecord("transfer-completed", id, TransferCompleted.class);
    }

    // (b) POSTING + not posted + nudge<age<fail -> RE-EMIT posting-requested, stays POSTING
    @Test
    void postingNotPostedReEmitsPostingRequested() {
        String id = "rec-b-" + System.nanoTime();
        seed(id, "POSTING", 60);
        when(accountsPostingClient.posted(id)).thenReturn(Optional.of(false));

        reconciler.reconcileOne(load(id));

        assertThat(statusOf(id)).isEqualTo("POSTING");
        // BANK-15: re-emitted posting-requested flows through the SAME externalization route and is now
        // keyed by the SOURCE account ("acc-src"), not the transferId — so re-drives also land on the
        // source account's single-writer partition. Match the record by its value's transferId.
        awaitPostingRequested(id);
    }

    // (c) POSTING + not posted + age>fail -> RE-DRIVEN, NOT failed. A `posted=false` snapshot does not
    // prove money never moved (a still-in-flight posting-requested could post AFTER we'd have failed it),
    // so the reconciler must NEVER auto-fail a POSTING transfer. It re-emits posting-requested and leaves
    // the status POSTING — only accounts' authority (ledger-posted / posting-rejected) terminalizes money.
    // This is the regression guard for the BANK-12 late-post-after-FAILED money-safety fix.
    @Test
    void postingNotPostedPastFailIsReDrivenNotFailed() {
        String id = "rec-c-" + System.nanoTime();
        seed(id, "POSTING", 600); // > fail (5m)
        when(accountsPostingClient.posted(id)).thenReturn(Optional.of(false));

        reconciler.reconcileOne(load(id));

        // MUST NOT be failed: the reconciler may not fabricate a FAILED over money it cannot prove never moved.
        assertThat(statusOf(id)).isEqualTo("POSTING");
        // BANK-15: re-emitted posting-requested flows through the SAME externalization route and is now
        // keyed by the SOURCE account ("acc-src"), not the transferId — so re-drives also land on the
        // source account's single-writer partition. Match the record by its value's transferId.
        awaitPostingRequested(id);
    }

    // (c2) POSTING + not posted + age>stuck-after -> emit acme.saga.stuck metric, status STILL POSTING.
    // This is the "page a human" signal for money that's genuinely stuck (accounts permanently
    // unreachable). State is NOT changed; re-drive also continues.
    @Test
    void postingNotPostedPastStuckThresholdEmitsStuckMetricButDoesNotFail() {
        String id = "rec-c2-" + System.nanoTime();
        seed(id, "POSTING", 1800); // > stuck-after (15m default)
        when(accountsPostingClient.posted(id)).thenReturn(Optional.of(false));

        double before = stuckCount();

        reconciler.reconcileOne(load(id));

        // Still POSTING — only accounts may terminalize money; the reconciler only surfaces it.
        assertThat(statusOf(id)).isEqualTo("POSTING");
        assertThat(stuckCount()).isEqualTo(before + 1.0);
        // Re-drive continues even when stuck.
        // BANK-15: re-emitted posting-requested flows through the SAME externalization route and is now
        // keyed by the SOURCE account ("acc-src"), not the transferId — so re-drives also land on the
        // source account's single-writer partition. Match the record by its value's transferId.
        awaitPostingRequested(id);
    }

    private double stuckCount() {
        io.micrometer.core.instrument.Counter c =
                meterRegistry.find("acme.saga.stuck").tag("status", "POSTING").counter();
        return c == null ? 0.0 : c.count();
    }

    // (d) REQUESTED + nudge<age<fail -> RE-EMIT transfer-requested
    @Test
    void requestedReEmitsTransferRequested() {
        String id = "rec-d-" + System.nanoTime();
        seed(id, "REQUESTED", 60);

        reconciler.reconcileOne(load(id));

        assertThat(statusOf(id)).isEqualTo("REQUESTED");
        awaitRecord("transfer-requested", id, TransferRequested.class);
    }

    // (e) APPROVED + age>fail -> FAILED(SAGA_TIMEOUT) + transfer-failed (pre-money, always safe)
    @Test
    void approvedPastFailTimesOut() {
        String id = "rec-e-" + System.nanoTime();
        seed(id, "APPROVED", 600);

        reconciler.reconcileOne(load(id));

        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM transfer WHERE id = ?", String.class, id))
                .isEqualTo("SAGA_TIMEOUT");
        awaitRecord("transfer-failed", id, TransferFailed.class);
    }

    // (f) POSTING + transport error (empty) -> UNCHANGED (never fail money on a failed query)
    @Test
    void postingTransportErrorLeavesUnchanged() {
        String id = "rec-f-" + System.nanoTime();
        seed(id, "POSTING", 600); // even past fail, a transport error must NOT fail it
        when(accountsPostingClient.posted(id)).thenReturn(Optional.empty());

        reconciler.reconcileOne(load(id));

        assertThat(statusOf(id)).isEqualTo("POSTING");
    }

    private <V> V awaitRecord(String topic, String key, Class<V> type) {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        try (Consumer<String, V> consumer = newConsumer(bootstrap, srUrl, topic + "-" + key + "-grp")) {
            consumer.subscribe(List.of(topic));
            @SuppressWarnings("unchecked")
            V[] holder = (V[]) new Object[1];
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, V> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, V> r : records) {
                    if (key.equals(r.key())) {
                        holder[0] = r.value();
                    }
                }
                assertThat(holder[0]).isNotNull();
            });
            return holder[0];
        }
    }

    /**
     * Await a re-emitted {@code posting-requested} record for the given transferId. BANK-15 keys this
     * topic by the SOURCE account, so we match on the record VALUE's transferId (and assert the Kafka
     * key is the source account "acc-src" — the single-writer routing guarantee).
     */
    private PostingRequested awaitPostingRequested(String transferId) {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        try (Consumer<String, PostingRequested> consumer =
                newConsumer(bootstrap, srUrl, "posting-requested-" + transferId + "-grp")) {
            consumer.subscribe(List.of("posting-requested"));
            java.util.concurrent.atomic.AtomicReference<ConsumerRecord<String, PostingRequested>> holder =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, PostingRequested> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, PostingRequested> r : records) {
                    if (transferId.equals(r.value().getTransferId())) {
                        holder.set(r);
                    }
                }
                assertThat(holder.get()).isNotNull();
            });
            // Single-writer routing: re-driven posting-requested is keyed by the source account.
            assertThat(holder.get().key()).isEqualTo("acc-src");
            return holder.get().value();
        }
    }

    private static <V> Consumer<String, V> newConsumer(String bootstrap, String srUrl, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return new KafkaConsumer<>(props);
    }
}
