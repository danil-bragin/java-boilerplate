package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.demo.avro.OrderEventAvro;
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
import org.springframework.context.annotation.Import;
import org.testcontainers.redpanda.RedpandaContainer;

@SpringBootTest
@Import({PostgresTestcontainersConfiguration.class, RedpandaTestcontainersConfiguration.class})
class AvroSchemaRegistryIT {

    @Autowired
    RedpandaContainer redpandaContainer;

    @Test
    void avroRecordRoundTripsThroughSchemaRegistry() {
        String bootstrap = redpandaContainer.getBootstrapServers().replaceFirst(".*://", "");
        String srUrl = redpandaContainer.getSchemaRegistryAddress();

        OrderEventAvro event = OrderEventAvro.newBuilder()
                .setOrderId(42L)
                .setSku("SKU-AVRO")
                .setQuantity(3)
                .build();

        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        producerProps.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        try (Producer<String, OrderEventAvro> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>("avro-orders", "k", event));
            producer.flush();
        }

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "avro-it");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        consumerProps.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, srUrl);
        consumerProps.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

        try (Consumer<String, OrderEventAvro> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of("avro-orders"));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, OrderEventAvro> records = consumer.poll(Duration.ofMillis(500));
                OrderEventAvro received = null;
                for (ConsumerRecord<String, OrderEventAvro> r : records) {
                    received = r.value();
                }
                assertThat(received).isNotNull();
                assertThat(received.getOrderId()).isEqualTo(42L);
                assertThat(received.getSku().toString()).isEqualTo("SKU-AVRO");
                assertThat(received.getQuantity()).isEqualTo(3);
            });
        }
    }
}
