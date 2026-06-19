package com.acme.bank.antifraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.bank.contracts.MoneyMapper;
import com.acme.bank.contracts.avro.TransferRequested;
import com.acme.money.Assets;
import com.acme.money.Money;
import com.acme.test.PostgresTestcontainersConfiguration;
import com.acme.test.RedpandaTestcontainersConfiguration;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * BANK-20: a poison Avro record (valid Avro value, business-logic exception in the handler) must reach
 * its {@code <topic>-dlt} dead-letter topic instead of retrying forever.
 *
 * <p>Before the fix the DLT {@link org.springframework.kafka.core.KafkaTemplate} overrode the value
 * serializer to {@code StringSerializer}, so re-publishing an Avro {@code SpecificRecord} threw a
 * {@code ClassCastException}, the DLT publish failed, and the record looped on the listener. The
 * type-delegating DLT serializer routes the Avro value through {@code KafkaAvroSerializer} so the
 * record lands on the DLT.
 */
@SpringBootTest
@Import({
    PostgresTestcontainersConfiguration.class,
    RedpandaTestcontainersConfiguration.class,
    AvroDltRoutingIT.PoisonListenerConfig.class,
    AvroDltRoutingIT.PoisonListener.class
})
class AvroDltRoutingIT {

    /** Topic whose Avro records always poison the listener, to prove the Avro→DLT path. */
    static final String POISON_TOPIC = "avro-poison";

    /**
     * Consumes the valid Avro {@code TransferRequested} (value deserializes fine) and ALWAYS throws — a
     * business-logic poison, NOT a deserialization failure. Kept separate from {@link PoisonListenerConfig}
     * so the {@code @KafkaListener} bean does not depend on a {@code @Bean} factory method of its own
     * configuration class (which would be a circular reference).
     */
    @Component
    static class PoisonListener {

        @KafkaListener(
                topics = POISON_TOPIC,
                groupId = "avro-poison-it",
                containerFactory = "avroPoisonListenerContainerFactory")
        void poison(TransferRequested event) {
            throw new IllegalStateException("poison: " + event.getTransferId());
        }
    }

    @TestConfiguration
    static class PoisonListenerConfig {

        @Bean
        DynamicPropertyRegistrar schemaRegistry(RedpandaContainer redpanda) {
            return registry -> {
                registry.add("spring.kafka.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
                registry.add(
                        "spring.kafka.consumer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
                registry.add(
                        "spring.kafka.producer.properties.schema.registry.url", redpanda::getSchemaRegistryAddress);
            };
        }

        @Bean
        org.apache.kafka.clients.admin.NewTopic avroPoisonTopic() {
            return TopicBuilder.name(POISON_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        org.apache.kafka.clients.admin.NewTopic avroPoisonDltTopic() {
            return TopicBuilder.name(POISON_TOPIC + "-dlt")
                    .partitions(1)
                    .replicas(1)
                    .build();
        }

        /**
         * Container factory for the poison listener, with a dedicated Avro {@link ConsumerFactory} pinned to
         * the testcontainer's schema registry (so {@code TransferRequested} deserializes into a
         * {@code SpecificRecord}) and wired with the messaging starter's DLT {@code DefaultErrorHandler} (the
         * {@code kafkaErrorHandler} bean under test). After {@code FixedBackOff(200ms x 2)} retries the
         * recoverer publishes the Avro value to {@code <topic>-dlt} — which only succeeds once the DLT
         * serializer is type-aware. The consumer factory is created inline (not a bean) so it never collides
         * with Boot's auto-configured {@code ConsumerFactory}.
         */
        @Bean
        ConcurrentKafkaListenerContainerFactory<String, Object> avroPoisonListenerContainerFactory(
                RedpandaContainer redpanda, DefaultErrorHandler kafkaErrorHandler) {
            String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
            props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, redpanda.getSchemaRegistryAddress());
            props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
            props.put("allow.auto.create.topics", false);

            ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setCommonErrorHandler(kafkaErrorHandler);
            return factory;
        }
    }

    @Autowired
    RedpandaContainer redpanda;

    @Test
    void poisonAvroRecordIsRoutedToDeadLetterTopic() {
        String bootstrap = redpanda.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpanda.getSchemaRegistryAddress();

        String transferId = "avro-dlt-it-1";
        try (Producer<String, TransferRequested> producer = newProducer(bootstrap, srUrl)) {
            producer.send(new ProducerRecord<>(
                    POISON_TOPIC, transferId, buildTransferRequested(transferId, Money.of("500.00", Assets.USD))));
            producer.flush();
        }

        // After FixedBackOff(200ms x 2) retries the record must DLT successfully — not loop forever.
        try (Consumer<String, TransferRequested> consumer = newConsumer(bootstrap, srUrl, "avro-dlt-it-consumer")) {
            consumer.subscribe(List.of(POISON_TOPIC + "-dlt"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, TransferRequested> records = consumer.poll(Duration.ofMillis(500));
                TransferRequested received = null;
                for (ConsumerRecord<String, TransferRequested> r : records) {
                    if (transferId.equals(r.key())) {
                        received = r.value();
                    }
                }
                assertThat(received)
                        .as("poison Avro record routed to %s-dlt", POISON_TOPIC)
                        .isNotNull();
                assertThat(received.getTransferId().toString()).isEqualTo(transferId);
            });
        }
    }

    private static TransferRequested buildTransferRequested(String transferId, Money amount) {
        return TransferRequested.newBuilder()
                .setTransferId(transferId)
                .setSourceAccountId("acc-src-test")
                .setDestinationAccountId("acc-dst-test")
                .setAmount(MoneyMapper.toAvro(amount))
                .setRequestedBy("test-user")
                .setRequestedAt(Instant.now())
                .build();
    }

    private static Producer<String, TransferRequested> newProducer(String bootstrap, String srUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        return new KafkaProducer<>(props);
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
