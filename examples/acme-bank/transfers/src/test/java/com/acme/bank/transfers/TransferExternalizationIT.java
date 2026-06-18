package com.acme.bank.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import an.awesome.pipelinr.Pipeline;
import com.acme.bank.contracts.avro.TransferRequested;
import com.acme.bank.transfers.application.InitiateTransferCommand;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.redpanda.RedpandaContainer;

@SpringBootTest(
        properties = {
            "spring.autoconfigure.exclude=com.acme.security.autoconfigure.SecurityAutoConfiguration",
            // Observation + W3C propagation must be on for the traceparent assertion below.
            "spring.kafka.template.observation-enabled=true",
            "management.tracing.sampling.probability=1.0"
        })
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    TransferExternalizationIT.SchemaRegistryProps.class
})
class TransferExternalizationIT {

    @Autowired
    Pipeline pipeline;

    @Autowired
    RedpandaContainer redpanda;

    @TestConfiguration
    static class SchemaRegistryProps {
        // Point the app's KafkaAvroSerializer + KafkaAvroDeserializer at the Redpanda Schema Registry.
        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> {
                registry.add(
                        "spring.kafka.producer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
                registry.add(
                        "spring.kafka.consumer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
            };
        }
    }

    @Test
    void externalizedRecordCarriesTraceparentHeader() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        pipeline.send(new InitiateTransferCommand(
                "t-trace-1", "acc-src", "acc-dst", Money.of("100.00", Assets.USD), "alice"));

        try (Consumer<String, TransferRequested> consumer = newConsumer(bootstrap, srUrl, "transfer-trace-it")) {
            consumer.subscribe(List.of("transfer-requested"));
            Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                ConsumerRecords<String, TransferRequested> records = consumer.poll(Duration.ofMillis(500));
                ConsumerRecord<String, TransferRequested> traced = null;
                for (ConsumerRecord<String, TransferRequested> r : records) {
                    if ("t-trace-1".equals(r.value().getTransferId().toString())) {
                        traced = r;
                    }
                }
                assertThat(traced).isNotNull();
                // With spring.kafka.template.observation-enabled + micrometer-tracing-bridge-otel +
                // a W3C propagator (see TracePropagationAutoConfiguration), the producer observation
                // injects the distributed trace context as a Kafka header. This is what lets a trace
                // span gateway→transfers→Kafka→accounts in Grafana.
                assertThat(traced.headers().lastHeader("traceparent"))
                        .as("externalized record must carry the W3C traceparent header")
                        .isNotNull();
            });
        }
    }

    @Test
    void initiatingATransferExternalizesTransferRequestedAsAvro() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String id = pipeline.send(
                new InitiateTransferCommand("t-ext-1", "acc-src", "acc-dst", Money.of("100.00", Assets.USD), "alice"));
        assertThat(id).isEqualTo("t-ext-1");

        try (Consumer<String, TransferRequested> consumer = newConsumer(bootstrap, srUrl, "transfer-ext-it")) {
            consumer.subscribe(List.of("transfer-requested"));
            Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                ConsumerRecords<String, TransferRequested> records = consumer.poll(Duration.ofMillis(500));
                TransferRequested received = null;
                for (ConsumerRecord<String, TransferRequested> r : records) {
                    received = r.value();
                }
                assertThat(received).isNotNull();
                assertThat(received.getTransferId().toString()).isEqualTo("t-ext-1");
                assertThat(received.getAmount().getAmount().toString()).isEqualTo("100");
                assertThat(received.getAmount().getAsset().toString()).isEqualTo("USD");
            });
        }
    }

    private static Consumer<String, TransferRequested> newConsumer(String bootstrap, String srUrl, String groupId) {
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
