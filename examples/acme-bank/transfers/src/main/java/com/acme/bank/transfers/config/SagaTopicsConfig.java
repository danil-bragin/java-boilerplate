package com.acme.bank.transfers.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the saga topics that the producer (transfers) owns. Spring's auto-configured
 * {@code KafkaAdmin} creates/updates every {@link NewTopic} bean on startup.
 *
 * <p>BANK-15: {@code posting-requested} is keyed by the SOURCE account (see
 * {@code TransferExternalizationConfig}). To turn that key into per-account write parallelism we make
 * the topic MULTI-PARTITION: each source account hashes to one partition, so one account's postings
 * serialize on a single partition/consumer (the single-writer lane) while different accounts spread
 * across partitions and are consumed in parallel. Partition count comes from
 * {@code acme.bank.topics.posting-requested.partitions} (default 6).
 *
 * <p>NOTE: each service with this {@link NewTopic} bean (transfers AND accounts) provisions/grows the
 * topic to the declared partition count at startup — {@code KafkaAdmin} GROWS an existing topic to the
 * declared count on context refresh. Increasing partitions on an EXISTING keyed topic re-maps keys (a
 * given account could move to a different partition, briefly disturbing the single-writer property
 * mid-flight), so it should only be done on a DRAINED topic in production. The example deploys fresh
 * ({@code docker compose down -v} drops the broker volume), so this is a one-time create. Replication
 * is 1 for the single-broker dev/Redpanda broker; PRODUCTION sets a higher replication factor at the
 * broker / topic-provisioning layer.
 */
@Configuration
class SagaTopicsConfig {

    @Bean
    NewTopic postingRequestedTopic(@Value("${acme.bank.topics.posting-requested.partitions:6}") int partitions) {
        return TopicBuilder.name("posting-requested")
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
