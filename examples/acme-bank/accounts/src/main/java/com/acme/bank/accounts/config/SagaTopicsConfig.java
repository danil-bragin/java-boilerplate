package com.acme.bank.accounts.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * BANK-15: accounts is the CONSUMER of {@code posting-requested}, but it declares the topic too.
 *
 * <p>The topic is keyed by SOURCE account and multi-partition (see transfers'
 * {@code TransferExternalizationConfig} / {@code SagaTopicsConfig}): each source account hashes to one
 * partition, so one account's postings serialize on a single partition/consumer (the single-writer lane)
 * while different accounts spread across partitions and are consumed in parallel.
 *
 * <p>accounts does NOT depend on transfers, so without this bean the accounts listener could subscribe
 * before transfers' {@code KafkaAdmin} provisions the topic — Redpanda would then auto-create
 * {@code posting-requested} at the broker default (1 partition) and ALL accounts would funnel to one
 * consumer thread, losing the entire BANK-15 benefit (and a degraded deploy where transfers is down
 * would stay at 1 partition). By declaring its OWN {@link NewTopic}, accounts' {@code KafkaAdmin}
 * provisions/grows the topic to the declared partition count at context refresh — BEFORE the listener
 * subscribes — so it can never silently consume a 1-partition topic regardless of start order. Both
 * services declaring the same topic is fine: {@code KafkaAdmin} create-or-modify is idempotent.
 *
 * <p>NOTE: each service with this bean provisions/grows the topic to the declared partition count at
 * startup. Increasing partitions on an EXISTING keyed topic re-maps keys (a given account could move to
 * a different partition, briefly disturbing the single-writer property mid-flight), so it should only be
 * done on a DRAINED topic in production. The example deploys fresh ({@code docker compose down -v} drops
 * the broker volume), so this is a one-time create. Replication is 1 for the single-broker dev/Redpanda
 * broker; PRODUCTION sets a higher replication factor at the broker / topic-provisioning layer.
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
