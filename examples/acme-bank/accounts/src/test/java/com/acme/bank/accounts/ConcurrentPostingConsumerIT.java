package com.acme.bank.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.contracts.avro.PostingRequested;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * BANK-15 rebalance-proxy / single-writer-per-account safety. Produces N {@code posting-requested}
 * records for the SAME source account but DISTINCT transferIds, against a multi-partition topic with
 * the accounts listener running at concurrency ≥ 2. Because they share the source-account KEY they all
 * hash to ONE partition → ONE consumer thread (the single-writer lane), so the account's read-modify-write
 * balance check serializes and the source can NEVER overdraw.
 *
 * <p>Funding covers exactly K of the N transfers, so the assertion is the single-writer OUTCOME:
 * exactly K post, the remaining N-K are rejected INSUFFICIENT_FUNDS, the source balance lands at 0
 * (never negative), and every posted transfer is Σ=0.
 *
 * <p>This also closes the rebalance edge: even if a transient rebalance briefly put two consumer
 * threads on the one partition, the retained BANK-11 pessimistic source lock + BANK-1 posting-PK anchor
 * would still serialize the writers and prevent an overdraft — that exact two-writer contention is
 * proved directly by the 8-thread {@code ConcurrentDebitIT}. Here we assert the steady-state
 * single-writer outcome end-to-end over Kafka.
 */
@SpringBootTest(properties = "acme.bank.topics.posting-requested.partitions=6")
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    ConcurrentPostingConsumerIT.SchemaRegistryProps.class
})
class ConcurrentPostingConsumerIT {

    private static final int N = 8;
    private static final int FUNDED = 5; // balance covers exactly 5 of the 8 transfers
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

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
    RedpandaContainer redpanda;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sameAccountConcurrentPostingsNeverOverdraw() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String source = "cpc-src-" + System.nanoTime();
        String dest = "cpc-dst-" + System.nanoTime();
        openAccount(source);
        openAccount(dest);
        // Fund the source for exactly FUNDED transfers; the rest must be rejected.
        seedBalance(source, AMOUNT.multiply(BigDecimal.valueOf(FUNDED)).toPlainString());

        String[] transferIds = new String[N];
        try (Producer<String, PostingRequested> producer = newProducer(bootstrap, srUrl)) {
            for (int i = 0; i < N; i++) {
                String transferId = source + "-t" + i; // DISTINCT transferIds, SAME source-account key
                transferIds[i] = transferId;
                producer.send(new ProducerRecord<>(
                        "posting-requested",
                        source, // KEY = source account → all on one partition → one writer thread
                        buildPostingRequested(transferId, source, dest, AMOUNT.toPlainString())));
            }
            producer.flush();
        }

        String inClause = "(" + "?,".repeat(N - 1) + "?)";

        // Wait until all N transfers have been processed (inbox-deduped → exactly N processed rows).
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Long processed = jdbc.queryForObject(
                    "SELECT count(*) FROM processed_messages WHERE listener = 'accounts' AND message_id IN " + inClause,
                    Long.class,
                    (Object[]) transferIds);
            assertThat(processed).isEqualTo((long) N);
        });

        // --- Single-writer outcome assertions ---

        // Exactly FUNDED transfers posted; the remaining N-FUNDED were rejected INSUFFICIENT_FUNDS
        // (no entries written). This is the single-writer outcome: had the balance check raced, MORE
        // than FUNDED would have slipped through and overdrawn the source.
        Long postedTransfers = jdbc.queryForObject(
                "SELECT count(DISTINCT transfer_id) FROM ledger_entry WHERE transfer_id IN " + inClause,
                Long.class,
                (Object[]) transferIds);
        assertThat(postedTransfers)
                .as("exactly the funded subset posts; the rest are INSUFFICIENT_FUNDS")
                .isEqualTo((long) FUNDED);

        // The source never overdraws: its derived balance is exactly 0 (FUNDED debits of AMOUNT against
        // a FUNDED*AMOUNT seed), and crucially NEVER negative.
        BigDecimal sourceBalance = jdbc.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE account_id = ? AND asset = 'USD'",
                BigDecimal.class,
                source);
        assertThat(sourceBalance)
                .as("source balance is exactly zero — never overdrawn")
                .isEqualByComparingTo("0");

        // Every transfer is double-entry balanced (Σ=0): no money created/destroyed. A rejected
        // transfer simply has no entries (sum over zero rows = 0).
        for (String transferId : transferIds) {
            BigDecimal sum = jdbc.queryForObject(
                    "SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE transfer_id = ?",
                    BigDecimal.class,
                    transferId);
            assertThat(sum)
                    .as("transfer %s is double-entry balanced (Σ=0)", transferId)
                    .isEqualByComparingTo("0");
        }
    }

    private void openAccount(String id) {
        jdbc.update(
                "INSERT INTO account(id, iban, status, asset) VALUES (?, ?, 'OPEN', 'USD') ON CONFLICT (id) DO NOTHING",
                id,
                "IBAN-" + id);
    }

    private void seedBalance(String accountId, String amount) {
        BigDecimal bd = new BigDecimal(amount);
        jdbc.update(
                "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset) "
                        + "VALUES (nextval('ledger_entry_seq'), ?, ?, ?, 'USD') ON CONFLICT DO NOTHING",
                "seed-" + accountId,
                accountId,
                bd);
        jdbc.update(
                "INSERT INTO ledger_entry(id, transfer_id, account_id, amount, asset) "
                        + "VALUES (nextval('ledger_entry_seq'), ?, 'funding', ?, 'USD') ON CONFLICT DO NOTHING",
                "seed-" + accountId,
                bd.negate());
    }

    private PostingRequested buildPostingRequested(String transferId, String src, String dst, String amount) {
        return PostingRequested.newBuilder()
                .setTransferId(transferId)
                .setSourceAccountId(src)
                .setDestinationAccountId(dst)
                .setAmount(com.acme.bank.contracts.avro.Money.newBuilder()
                        .setAmount(amount)
                        .setAsset("USD")
                        .build())
                .build();
    }

    private static <V> Producer<String, V> newProducer(String bootstrap, String srUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        return new KafkaProducer<>(props);
    }
}
