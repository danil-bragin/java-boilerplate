package com.acme.bank.antifraud.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * BANK-19b: a BATCH {@link ConcurrentKafkaListenerContainerFactory} for the antifraud screening listener.
 *
 * <p>Screening is an IDEMPOTENT, NON-MONEY hop: antifraud holds no balances and a screening decision has
 * no money meaning until accounts posts (the money POSTING path stays per-posting in accounts, never
 * batched). So a poll of N {@code transfer-requested} records can be screened in ONE transaction —
 * amortizing the commit/WAL-fsync across the batch — WITHOUT any partial-failure money risk. Per-record
 * inbox dedup is preserved INSIDE the batch loop (the listener calls {@code inbox.firstTime(..)} per
 * record), so redelivery still skips already-screened transfers; a batch is delivered in offset order and
 * keying by transferId is untouched, so per-transfer ordering is unchanged.
 *
 * <p>The factory reuses Boot's auto-configured {@link ConsumerFactory} (same Avro deserializers, group-id,
 * earliest, fetch tuning from application.yaml) and only flips {@code batchListener=true} + mirrors the
 * per-partition concurrency, so batching changes ONLY the dispatch shape — never delivery, ordering, or
 * dedup semantics. The default error handler re-polls a failed batch; the inbox makes the replay idempotent.
 */
@Configuration
class BatchListenerConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> batchListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, KafkaProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // BATCH mode: the listener method receives List<EventType> and the whole poll commits once.
        factory.setBatchListener(true);
        // Mirror the per-partition parallelism the default factory uses (one thread per partition, <= 6).
        Integer concurrency = properties.getListener().getConcurrency();
        factory.setConcurrency(concurrency != null ? concurrency : 6);
        return factory;
    }
}
