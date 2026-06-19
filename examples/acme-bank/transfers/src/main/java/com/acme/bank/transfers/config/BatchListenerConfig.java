package com.acme.bank.transfers.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * BANK-21: a BATCH {@link ConcurrentKafkaListenerContainerFactory} for the transfers consume-side saga
 * listeners (screening-result + posting-result).
 *
 * <p>These listeners only ADVANCE the transfer saga's status (REQUESTED→POSTING / →COMPLETED / →FAILED)
 * and emit the next saga event — they hold NO balances and move NO money (the money POSTING lives in
 * accounts). So a poll of N result records can be applied in ONE transaction, amortizing the
 * commit/WAL-fsync across the batch. Per-record inbox dedup is preserved INSIDE the batch loop (each
 * record is {@code inbox.firstTime(..)}-guarded), so redelivery still skips an already-advanced transfer;
 * records arrive in offset order and keying by transferId is unchanged, so per-transfer ordering holds.
 *
 * <p>Unlike the BANK-19b non-money batch factories, this one is wired through Boot's
 * {@link ConcurrentKafkaListenerContainerFactoryConfigurer} so the auto-detected {@code CommonErrorHandler}
 * (the messaging starter's {@code DefaultErrorHandler} → DLT) is attached. That makes a per-record
 * {@code BatchListenerFailedException(record)} on a TRUE infra error commit the offsets of the already-
 * processed records and retry from the failed one — and the inbox makes that retry idempotent. A per-record
 * BUSINESS condition (already-terminal / unknown transfer) is NOT an error: the listener simply skips that
 * record, it does not fail the batch.
 */
@Configuration
class BatchListenerConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> batchListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        // configurer applies Boot's listener properties (concurrency, auto-startup, fetch tuning) AND the
        // auto-detected common error handler (the messaging starter's DefaultErrorHandler -> DLT), which is
        // batch-aware and honors BatchListenerFailedException(record) for commit-up-to-i + retry-from-i.
        configurer.configure(factory, consumerFactory);
        // BATCH mode: the listener method receives List<ConsumerRecord<..>> and the whole poll commits once.
        factory.setBatchListener(true);
        return factory;
    }
}
