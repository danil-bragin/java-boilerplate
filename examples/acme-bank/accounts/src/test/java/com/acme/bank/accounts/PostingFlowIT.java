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
import java.util.List;
import java.util.Map;
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

@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    PostingFlowIT.SchemaRegistryProps.class
})
class PostingFlowIT {

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
                "INSERT INTO account(id, iban, status) VALUES (?, ?, 'OPEN') ON CONFLICT (id) DO NOTHING",
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

    @Test
    void fundedTransferPostsAndEmitsLedgerPosted() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "posting-flow-it-1";

        openAccount("src-pf-1");
        openAccount("dst-pf-1");
        seedBalance("src-pf-1", "500.00");

        try (Producer<String, PostingRequested> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "posting-requested", transferId, buildPostingRequested(transferId, "src-pf-1", "dst-pf-1", "100")));
            producer.flush();
        }

        try (Consumer<String, LedgerPosted> consumer = newConsumer(bootstrap, srUrl, "posting-flow-it-1-grp")) {
            consumer.subscribe(List.of("ledger-posted"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, LedgerPosted> records = consumer.poll(Duration.ofMillis(500));
                LedgerPosted received = null;
                for (ConsumerRecord<String, LedgerPosted> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId()).isEqualTo(transferId);
            });
        }

        // Assert ledger is balanced (Σ=0)
        BigDecimal sum = jdbc.queryForObject(
                "SELECT sum(amount) FROM ledger_entry WHERE transfer_id = ?", BigDecimal.class, transferId);
        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void insufficientFundsEmitsPostingRejected() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "posting-flow-it-2";

        openAccount("src-pf-2");
        openAccount("dst-pf-2");
        seedBalance("src-pf-2", "10.00");

        try (Producer<String, PostingRequested> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "posting-requested", transferId, buildPostingRequested(transferId, "src-pf-2", "dst-pf-2", "100")));
            producer.flush();
        }

        try (Consumer<String, PostingRejected> consumer = newConsumer(bootstrap, srUrl, "posting-flow-it-2-grp")) {
            consumer.subscribe(List.of("posting-rejected"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, PostingRejected> records = consumer.poll(Duration.ofMillis(500));
                PostingRejected received = null;
                for (ConsumerRecord<String, PostingRejected> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getReason()).isEqualTo("INSUFFICIENT_FUNDS");
            });
        }

        // No ledger entries for the rejected transfer
        Long entries =
                jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = ?", Long.class, transferId);
        assertThat(entries).isEqualTo(0L);
    }

    @Test
    void duplicatePostingRequestIsIdempotent() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "posting-flow-it-dup-1";

        openAccount("src-pf-dup");
        openAccount("dst-pf-dup");
        seedBalance("src-pf-dup", "500.00");

        PostingRequested event = buildPostingRequested(transferId, "src-pf-dup", "dst-pf-dup", "50");

        try (Producer<String, PostingRequested> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>("posting-requested", transferId, event));
            producer.send(new ProducerRecord<>("posting-requested", transferId, event)); // redelivery
            producer.flush();
        }

        // Wait for processing
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM processed_messages WHERE listener = 'accounts' AND message_id = ?",
                    Long.class,
                    transferId);
            assertThat(count).isEqualTo(1L);
        });

        // Only 2 ledger entries (one debit, one credit) — not 4
        Long entries =
                jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE transfer_id = ?", Long.class, transferId);
        assertThat(entries).isEqualTo(2L);
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
