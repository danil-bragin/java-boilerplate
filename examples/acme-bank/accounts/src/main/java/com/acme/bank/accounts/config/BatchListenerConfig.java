package com.acme.bank.accounts.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * BANK-21: a BATCH {@link ConcurrentKafkaListenerContainerFactory} for the accounts ledger-posting
 * listener — the MONEY path. This is the highest-care batching change in the project, so the safety
 * argument is spelled out here and enforced by {@code BatchOverdraftIT}.
 *
 * <p><b>Why batching the money posting is safe (in fact STRICTER than per-record):</b> {@code
 * posting-requested} is keyed by the SOURCE accountId (BANK-15), so all of one account's postings
 * co-partition → land in ONE poll batch → are processed in ONE transaction by the batch listener. Within
 * that single transaction each posting takes the BANK-11 {@code findByIdForUpdate} source lock (re-entrant
 * within the one tx) and then reads its derived balance with {@code SELECT SUM(...)}. Because the prior
 * postings in the same batch already wrote their ledger entries in THIS uncommitted transaction, that SUM
 * SEES them — so the postings serialize and an overdraft is impossible. (Per-record each posting only saw
 * COMMITTED entries; in-batch it additionally sees the uncommitted siblings, which is strictly safer.)
 *
 * <p><b>Rejections are results, only infra errors fail the batch.</b> A business rejection
 * (INSUFFICIENT_FUNDS / ACCOUNT_NOT_OPERATIONAL / ACCOUNT_ASSET_MISMATCH) is a normal {@code
 * PostTransferResult.rejected(..)} return — the handler does NOT throw — so it emits {@code
 * posting-rejected} and does NOT roll back the batch's successful siblings. Only a TRUE infra error (DB
 * down) propagates; the listener wraps it in {@code BatchListenerFailedException(record, i)} so the
 * container commits offsets up to i and re-drives from i, and the BANK-1 posting-PK anchor makes the
 * re-drive idempotent (an already-posted transfer short-circuits). Any posting that a rolled-back partial
 * commit leaves undone is also re-driven by the transfers {@code SagaReconciler} against the ledger — so no
 * money guarantee depends on the batch commit boundary; overdraft-impossibility comes from the lock + in-tx
 * SUM, double-post-impossibility from the anchor.
 *
 * <p>Wired through Boot's {@link ConcurrentKafkaListenerContainerFactoryConfigurer} so the messaging
 * starter's {@code DefaultErrorHandler} → DLT (batch-aware, honors {@code BatchListenerFailedException}) is
 * attached, and concurrency/auto-startup/fetch tuning from {@code application.yaml} are preserved
 * (per-partition single-threaded → a source account's postings stay on ONE lane).
 */
@Configuration
class BatchListenerConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> batchListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        return factory;
    }
}
