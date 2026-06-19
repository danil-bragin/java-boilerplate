package com.acme.messaging.autoconfigure;

import com.acme.messaging.Inbox;
import com.acme.messaging.JdbcInbox;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.util.backoff.FixedBackOff;

/** Provides a JDBC {@link Inbox} and a default Kafka error handler that routes poison records to a DLT. */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, KafkaAutoConfiguration.class})
@ConditionalOnClass(JdbcTemplate.class)
public class MessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    /** Counter incremented once per record routed to a dead-letter topic, tagged by source topic. */
    static final String DLT_COUNTER = "acme.saga.dlt";

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public Inbox inbox(JdbcTemplate jdbcTemplate) {
        return new JdbcInbox(jdbcTemplate);
    }

    /**
     * A {@link DefaultErrorHandler} that routes failed records to a dead-letter topic after
     * {@code FixedBackOff(200ms × 2)} retries.
     *
     * <p>Derives a dedicated DLT {@link KafkaTemplate} from the auto-configured
     * {@link ProducerFactory} (which already has the correct {@code bootstrap.servers}). Keys are always
     * {@link String} (transferId / accountId), so the key serializer is fixed to {@link StringSerializer}.
     *
     * <p>The VALUE serializer is a type-aware {@link DelegatingByTypeSerializer} so a poison record of ANY
     * value type reaches the DLT instead of throwing: {@code byte[]} → {@link ByteArraySerializer},
     * {@link String} → {@link StringSerializer}, and (when Confluent/Avro are on the classpath AND a schema
     * registry is configured) Avro {@code SpecificRecord}/{@code GenericRecord} →
     * {@code io.confluent.kafka.serializers.KafkaAvroSerializer}. The Avro delegate is added ONLY when both
     * those classes are present and a {@code schema.registry.url} is available, so a String-only service
     * (e.g. the demo) without Confluent on the classpath still constructs and works.
     *
     * <p>Before this change the DLT template overrode the value serializer to {@link StringSerializer}
     * unconditionally; an Avro {@code SpecificRecord} value (business-logic poison, valid Avro) then failed
     * to re-publish with a {@code ClassCastException}, the DLT publish failed, and the record retried forever
     * (BANK-20).
     */
    @Bean
    @ConditionalOnBean(ProducerFactory.class)
    @ConditionalOnMissingBean
    public DefaultErrorHandler kafkaErrorHandler(
            ProducerFactory<Object, Object> producerFactory, ObjectProvider<MeterRegistry> meterRegistry) {
        // Build a dedicated DLT producer factory from the auto-configured one's configuration (inheriting
        // bootstrap.servers, security, schema.registry.url, etc.). The key is always String; the value
        // serializer is type-delegating so String, byte[] and (optionally) Avro values all re-publish.
        Map<String, Object> dltConfig = new HashMap<>(producerFactory.getConfigurationProperties());
        // Serializers are supplied as instances below, so drop any class-name config that would otherwise
        // make Kafka instantiate (and configure) a single fixed serializer for every value type.
        dltConfig.remove("key.serializer");
        dltConfig.remove("value.serializer");
        Serializer<Object> dltValueSerializer = buildDltValueSerializer(dltConfig);
        DefaultKafkaProducerFactory<String, Object> dltFactory =
                new DefaultKafkaProducerFactory<>(dltConfig, new StringSerializer(), dltValueSerializer);
        KafkaTemplate<String, Object> dltTemplate = new KafkaTemplate<>(dltFactory);

        // Custom destination resolver: this is exactly where the <topic>-dlt target is computed, so
        // it's the natural seam to surface a poisoned message for alerting (the reconciler handles
        // silent hangs; this metric handles poison). Increment acme.saga.dlt (tagged by the source
        // topic) and WARN, then resolve to the default "<topic>-dlt" partition.
        MeterRegistry registry = meterRegistry.getIfAvailable();
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(dltTemplate, (record, exception) -> {
                    String topic = record.topic();
                    if (registry != null) {
                        registry.counter(DLT_COUNTER, "topic", topic).increment();
                    }
                    log.warn(
                            "routing poison record to DLT: topic={} partition={} offset={} cause={}",
                            topic,
                            record.partition(),
                            record.offset(),
                            exception.toString());
                    // Spring Kafka's default destination: same partition on "<topic>-dlt".
                    return new TopicPartition(topic + "-dlt", record.partition());
                });
        return new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2L));
    }

    /**
     * Builds the type-aware DLT value serializer. {@code byte[]} → {@link ByteArraySerializer},
     * {@link String} → {@link StringSerializer}. When Confluent's {@code KafkaAvroSerializer} and Avro's
     * {@code SpecificRecord} are on the classpath AND a {@code schema.registry.url} is configured, Avro
     * records (matched by the {@code SpecificRecord}/{@code GenericRecord}/{@code IndexedRecord}
     * interfaces) are routed through a {@code KafkaAvroSerializer}.
     *
     * <p>Everything beyond {@code byte[]}/{@code String} is resolved reflectively so this starter never
     * hard-depends on Confluent/Avro — a String-only service without them on the classpath still gets a
     * working delegating serializer. The map is a {@link LinkedHashMap}; the Avro interface entries go last
     * so they are only consulted (via {@code assignableFrom=true}) after the exact {@code byte[]}/{@code
     * String} matches.
     */
    @SuppressWarnings("unchecked")
    private static Serializer<Object> buildDltValueSerializer(Map<String, Object> producerConfig) {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(String.class, new StringSerializer());

        Object schemaRegistryUrl = producerConfig.get("schema.registry.url");
        boolean hasSchemaRegistry =
                schemaRegistryUrl != null && !schemaRegistryUrl.toString().isBlank();
        if (hasSchemaRegistry) {
            addAvroDelegateIfAvailable(delegates, producerConfig);
        }

        // assignableFrom=true: the value is a concrete Avro class, so match it against the SpecificRecord /
        // GenericRecord / IndexedRecord interface keys (and String/byte[] by exact type).
        return (Serializer<Object>) (Serializer<?>) new DelegatingByTypeSerializer(delegates, true);
    }

    /**
     * Reflectively adds Avro interface → {@code KafkaAvroSerializer} mappings, but only if both Confluent's
     * {@code KafkaAvroSerializer} and Avro's {@code SpecificRecord} are present. Failure to load either
     * (a String-only service) silently skips the Avro path, leaving the String/byte[] delegates intact.
     */
    private static void addAvroDelegateIfAvailable(
            Map<Class<?>, Serializer<?>> delegates, Map<String, Object> producerConfig) {
        try {
            Class<?> serializerClass = Class.forName("io.confluent.kafka.serializers.KafkaAvroSerializer");
            // Probe Avro is on the classpath too; if not, this throws and we skip the Avro path.
            Class<?> specificRecord = Class.forName("org.apache.avro.specific.SpecificRecord");
            Class<?> genericRecord = Class.forName("org.apache.avro.generic.GenericRecord");
            Class<?> indexedRecord = Class.forName("org.apache.avro.generic.IndexedRecord");

            @SuppressWarnings("unchecked")
            Serializer<Object> avroSerializer = (Serializer<Object>)
                    serializerClass.getDeclaredConstructor().newInstance();
            // Configure the KafkaAvroSerializer as a value serializer with the producer's schema.registry.url
            // (and any other Confluent props that ride along in the producer config).
            avroSerializer.configure(stringKeyedConfig(producerConfig), false);

            delegates.put(specificRecord, avroSerializer);
            delegates.put(genericRecord, avroSerializer);
            delegates.put(indexedRecord, avroSerializer);
        } catch (ReflectiveOperationException | LinkageError ex) {
            // Confluent/Avro not on the classpath (or incompatible): leave the String/byte[] delegates only.
            log.debug("Avro DLT serializer delegate not available; DLT handles String/byte[] only: {}", ex.toString());
        }
    }

    /** Confluent's {@code Serializer.configure} expects a {@code Map<String, ?>}; the producer config keys are already Strings. */
    private static Map<String, Object> stringKeyedConfig(Map<String, Object> producerConfig) {
        Map<String, Object> copy = new HashMap<>();
        producerConfig.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /**
     * A {@link ConcurrentKafkaListenerContainerFactory} that delivers raw deserialized values
     * (e.g. {@code String} from {@code StringDeserializer}) directly to listener methods without
     * applying any JSON message converter. This avoids interference when Spring Modulith's
     * {@code ByteArrayJsonMessageConverter} is on the classpath and the listener wants a plain
     * {@code String}.
     *
     * <p>Routes through Boot's {@link ConcurrentKafkaListenerContainerFactoryConfigurer} so the
     * auto-detected {@code CommonErrorHandler} (our {@code kafkaErrorHandler} DefaultErrorHandler →
     * DLT) is attached to the factory. The record message converter is then overridden to pass raw
     * String payloads through.
     */
    @Bean
    @ConditionalOnBean(ConsumerFactory.class)
    @ConditionalOnMissingBean(name = "stringKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> stringKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        // configurer applies Boot's listener properties AND the auto-detected common error handler
        // (our kafkaErrorHandler DefaultErrorHandler -> DLT). Then override only the converter so raw
        // String payloads pass through (bypassing Modulith's global ByteArrayJsonMessageConverter).
        configurer.configure(factory, consumerFactory);
        factory.setRecordMessageConverter(new MessagingMessageConverter());
        return factory;
    }
}
