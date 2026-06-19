package com.acme.bank.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.contracts.avro.LedgerPosted;
import com.acme.bank.contracts.avro.PostingRejected;
import com.acme.bank.contracts.avro.PostingRequested;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
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
 * BANK-21 NON-NEGOTIABLE money-safety gate for batching the ledger posting.
 *
 * <p>Produces 8 {@code posting-requested} for the SAME source account — keyed by that source accountId
 * (BANK-15), so they co-partition onto ONE partition, land in ONE poll batch, and are processed in ONE
 * transaction by the batch listener. The source is funded for exactly 5 of the 8 equal debits. The gate
 * asserts batching does NOT weaken overdraft prevention:
 *
 * <ul>
 *   <li>exactly 5 transfers post ({@code ledger-posted}); the other 3 are {@code posting-rejected}
 *       INSUFFICIENT_FUNDS;
 *   <li>the source derived balance is exactly 0 and NEVER negative;
 *   <li>every posted transfer is balanced (Σ of its ledger entries == 0);
 *   <li>exactly the 5 postings' entries are written (5 × 2 = 10 entries, 5 posting rows).
 * </ul>
 *
 * <p>This proves the in-tx serialization (each later posting's derived-balance SUM sees the earlier ones'
 * uncommitted entries within the one batch tx) makes overdraft impossible — in fact STRICTER than the
 * per-record path — and that a business rejection is a normal result emitting {@code posting-rejected},
 * NOT a batch rollback that would lose its successful siblings.
 */
@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    BatchOverdraftIT.SchemaRegistryProps.class
})
class BatchOverdraftIT {

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

    private BigDecimal balanceOf(String accountId) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM ledger_entry WHERE account_id = ? AND asset = 'USD'",
                BigDecimal.class,
                accountId);
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

    @Test
    void sameSourceBatchCannotOverdraw() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String src = "batch-od-src";
        String dst = "batch-od-dst";
        openAccount(src);
        openAccount(dst);
        // Funded for exactly 5 of the 8 equal 20.00 debits (100.00 = 5 × 20.00).
        seedBalance(src, "100.00");

        int n = 8;
        // Produce all 8 to ONE partition by keying on the SOURCE account (BANK-15 keying) so the whole
        // burst lands in one poll batch and is posted in one transaction.
        try (Producer<String, PostingRequested> producer = newProducer(bootstrap, srUrl)) {
            for (int i = 0; i < n; i++) {
                String transferId = "batch-od-" + i;
                producer.send(new ProducerRecord<>(
                        "posting-requested", src, buildPostingRequested(transferId, src, dst, "20.00")));
            }
            producer.flush();
        }

        // Collect the saga outcomes: exactly 5 ledger-posted + 3 posting-rejected(INSUFFICIENT_FUNDS).
        Set<String> posted = new HashSet<>();
        Set<String> rejected = new HashSet<>();
        try (Consumer<String, Object> consumer = newConsumer(bootstrap, srUrl, "batch-od-grp")) {
            consumer.subscribe(List.of("ledger-posted", "posting-rejected"));
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, Object> r : records) {
                    Object v = r.value();
                    if (v instanceof LedgerPosted lp) {
                        posted.add(lp.getTransferId().toString());
                    } else if (v instanceof PostingRejected pr) {
                        assertThat(pr.getReason().toString()).isEqualTo("INSUFFICIENT_FUNDS");
                        rejected.add(pr.getTransferId().toString());
                    }
                }
                assertThat(posted).hasSize(5);
                assertThat(rejected).hasSize(3);
            });
        }

        // Source derived balance is exactly 0 and NEVER negative — overdraft did not slip through batching.
        BigDecimal sourceBalance = balanceOf(src);
        assertThat(sourceBalance).isEqualByComparingTo("0.00");
        assertThat(sourceBalance).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Exactly the 5 successful postings were written: 5 × 2 = 10 ledger entries, 5 posting rows.
        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE transfer_id LIKE 'batch-od-%'", Long.class);
        assertThat(entries).isEqualTo(10L);
        Long postings =
                jdbc.queryForObject("SELECT count(*) FROM posting WHERE transfer_id LIKE 'batch-od-%'", Long.class);
        assertThat(postings).isEqualTo(5L);

        // Every posted transfer is balanced (Σ of its two ledger entries == 0).
        for (String transferId : posted) {
            BigDecimal sum = jdbc.queryForObject(
                    "SELECT sum(amount) FROM ledger_entry WHERE transfer_id = ?", BigDecimal.class, transferId);
            assertThat(sum).isEqualByComparingTo("0");
        }

        // The 3 rejected transfers wrote NO ledger entries.
        for (String transferId : rejected) {
            Long e = jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE transfer_id = ?", Long.class, transferId);
            assertThat(e).isEqualTo(0L);
        }
    }

    private static <V> Producer<String, V> newProducer(String bootstrap, String srUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        return new KafkaProducer<>(props);
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
