package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.contracts.avro.LedgerPosted;
import com.acme.bank.contracts.avro.PostingRejected;
import com.acme.bank.contracts.avro.PostingRequested;
import com.acme.bank.contracts.avro.TransferCompleted;
import com.acme.bank.contracts.avro.TransferFailed;
import com.acme.bank.contracts.avro.TransferScreened;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
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

@SpringBootTest(properties = "spring.autoconfigure.exclude=com.acme.security.autoconfigure.SecurityAutoConfiguration")
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    TransferAdvanceIT.SchemaRegistryProps.class
})
class TransferAdvanceIT {

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

    private void seedTransfer(String transferId, String status) {
        jdbc.update(
                "INSERT INTO transfer(id, source_account_id, destination_account_id, amount, asset, requested_by, status) "
                        + "VALUES (?, 'acc-src', 'acc-dst', 100, 'USD', 'alice', ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                transferId,
                status);
    }

    @Test
    void approvedScreeningAdvancesToPostingAndEmitsPostingRequested() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "advance-it-approve-1";

        seedTransfer(transferId, "REQUESTED");

        try (Producer<String, TransferScreened> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "transfer-screened",
                    transferId,
                    TransferScreened.newBuilder()
                            .setTransferId(transferId)
                            .setApproved(true)
                            .build()));
            producer.flush();
        }

        try (Consumer<String, PostingRequested> consumer = newConsumer(bootstrap, srUrl, "advance-it-approve-1-grp")) {
            consumer.subscribe(List.of("posting-requested"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, PostingRequested> records = consumer.poll(Duration.ofMillis(500));
                PostingRequested received = null;
                for (ConsumerRecord<String, PostingRequested> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId()).isEqualTo(transferId);
            });
        }

        // Status should be POSTING
        String status = jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("POSTING");
    }

    @Test
    void rejectedScreeningFailsTransferAndEmitsTransferFailed() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "advance-it-reject-1";

        seedTransfer(transferId, "REQUESTED");

        try (Producer<String, TransferScreened> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "transfer-screened",
                    transferId,
                    TransferScreened.newBuilder()
                            .setTransferId(transferId)
                            .setApproved(false)
                            .setReason("AMOUNT_LIMIT")
                            .build()));
            producer.flush();
        }

        try (Consumer<String, TransferFailed> consumer = newConsumer(bootstrap, srUrl, "advance-it-reject-1-grp")) {
            consumer.subscribe(List.of("transfer-failed"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, TransferFailed> records = consumer.poll(Duration.ofMillis(500));
                TransferFailed received = null;
                for (ConsumerRecord<String, TransferFailed> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId()).isEqualTo(transferId);
            });
        }

        String status = jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void ledgerPostedCompletesTransfer() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "advance-it-complete-1";

        seedTransfer(transferId, "POSTING");

        try (Producer<String, LedgerPosted> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "ledger-posted",
                    transferId,
                    LedgerPosted.newBuilder().setTransferId(transferId).build()));
            producer.flush();
        }

        try (Consumer<String, TransferCompleted> consumer =
                newConsumer(bootstrap, srUrl, "advance-it-complete-1-grp")) {
            consumer.subscribe(List.of("transfer-completed"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, TransferCompleted> records = consumer.poll(Duration.ofMillis(500));
                TransferCompleted received = null;
                for (ConsumerRecord<String, TransferCompleted> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId()).isEqualTo(transferId);
            });
        }

        String status = jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    void postingRejectedFailsTransfer() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "advance-it-posting-rejected-1";

        seedTransfer(transferId, "POSTING");

        try (Producer<String, PostingRejected> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    "posting-rejected",
                    transferId,
                    PostingRejected.newBuilder()
                            .setTransferId(transferId)
                            .setReason("INSUFFICIENT_FUNDS")
                            .build()));
            producer.flush();
        }

        try (Consumer<String, TransferFailed> consumer =
                newConsumer(bootstrap, srUrl, "advance-it-posting-rejected-1-grp")) {
            consumer.subscribe(List.of("transfer-failed"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, TransferFailed> records = consumer.poll(Duration.ofMillis(500));
                TransferFailed received = null;
                for (ConsumerRecord<String, TransferFailed> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received).isNotNull();
                assertThat(received.getReason()).isEqualTo("INSUFFICIENT_FUNDS");
            });
        }

        String status = jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void duplicateScreeningIsIdempotent() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();
        String transferId = "advance-it-dup-1";

        seedTransfer(transferId, "REQUESTED");

        TransferScreened event = TransferScreened.newBuilder()
                .setTransferId(transferId)
                .setApproved(true)
                .build();

        try (Producer<String, TransferScreened> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>("transfer-screened", transferId, event));
            producer.send(new ProducerRecord<>("transfer-screened", transferId, event)); // redelivery
            producer.flush();
        }

        // Wait for processing then confirm inbox has exactly 1 record
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM processed_messages WHERE listener = 'transfers-screening' AND message_id = ?",
                    Long.class,
                    transferId);
            assertThat(count).isEqualTo(1L);
        });
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
