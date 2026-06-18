package com.acme.bank.gateway.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * BANK-19b: a BATCH {@link ConcurrentKafkaListenerContainerFactory} for the gateway transfer_view
 * projection listeners.
 *
 * <p>The projection is a REBUILDABLE CQRS read model — it holds no balances and enforces no money
 * invariants, consumes from {@code earliest}, and recovers from loss by re-consuming the saga log. So a
 * poll of N projection events can be applied in ONE transaction (amortizing the commit; combined with
 * {@code synchronous_commit=off} on the gateway datasource the per-record fsync is already gone) WITHOUT
 * money risk. Per-record inbox dedup + the monotonic rank guard in {@code TransferView.advanceTo} are kept
 * inside the batch loop, so redelivered/out-of-order events never regress a status and dedup holds.
 *
 * <p>Reuses Boot's auto-configured {@link ConsumerFactory} and only flips {@code batchListener=true} +
 * mirrors per-partition concurrency, so batching changes only the dispatch shape, not delivery semantics.
 */
@Configuration
class BatchListenerConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> batchListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, KafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        Integer concurrency = properties.getListener().getConcurrency();
        factory.setConcurrency(concurrency != null ? concurrency : 6);
        return factory;
    }
}
