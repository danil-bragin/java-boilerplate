package com.acme.messaging.autoconfigure;

import com.acme.messaging.Inbox;
import com.acme.messaging.JdbcInbox;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
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
     * {@link ProducerFactory} (which already has the correct {@code bootstrap.servers}), overriding
     * only the key/value serializers to {@link StringSerializer}. This avoids a
     * {@code ByteArraySerializer} type mismatch when {@code String}-valued records consumed via
     * {@code StringDeserializer} need to be re-published to the dead-letter topic.
     */
    @Bean
    @ConditionalOnBean(ProducerFactory.class)
    @ConditionalOnMissingBean
    public DefaultErrorHandler kafkaErrorHandler(
            ProducerFactory<Object, Object> producerFactory, ObjectProvider<MeterRegistry> meterRegistry) {
        // Derive a DLT producer factory from the auto-configured one (inheriting bootstrap.servers,
        // security, etc.) but override serializers to String so String-valued consumer records can
        // be re-published without a ByteArraySerializer type mismatch. The raw cast is safe because
        // we are overriding the serializer classes to StringSerializer.
        @SuppressWarnings({"rawtypes", "unchecked"})
        ProducerFactory<String, String> dltFactory = (ProducerFactory<String, String>)
                (ProducerFactory) producerFactory.copyWithConfigurationOverride(Map.of(
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        KafkaTemplate<String, String> dltTemplate = new KafkaTemplate<>(dltFactory);

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
