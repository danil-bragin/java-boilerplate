package com.acme.bank.accounts.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * BANK-16: declares EVERY saga topic that accounts PRODUCES OR CONSUMES as a multi-partition topic.
 * Spring's auto-configured {@code KafkaAdmin} creates/grows each {@link NewTopic} bean to the declared
 * partition count on context refresh — BEFORE the listener subscribes.
 *
 * <p>accounts consumes {@code posting-requested} and produces {@code ledger-posted} and
 * {@code posting-rejected}. Declaring the consumed topic here too (the deterministic-provisioning pattern
 * from BANK-15) means accounts never silently subscribes to an auto-created 1-partition topic regardless of
 * whether transfers (the producer) is up: its OWN {@code KafkaAdmin} provisions/grows
 * {@code posting-requested} to the declared count, and {@code allow.auto.create.topics=false} turns a
 * missing topic into a loud error rather than a 1-partition funnel that would collapse per-account write
 * parallelism onto one consumer thread. Both services declaring the same topic is fine — {@code KafkaAdmin}
 * create-or-modify is idempotent.
 *
 * <p>Partition count comes from the shared {@code acme.bank.topics.partitions} (default 6).
 * {@code posting-requested} is keyed by SOURCE account (see transfers' {@code TransferExternalizationConfig}):
 * each source account hashes to one partition, so one account's postings serialize on a single
 * partition/consumer (the single-writer lane) while different accounts spread across partitions and are
 * consumed in parallel. The BANK-11 pessimistic source lock and the BANK-1 posting-PK anchor remain as
 * correctness backstops at the rebalance edge.
 *
 * <p>NOTE: increasing partitions on an EXISTING keyed topic re-maps keys (a given account could move to a
 * different partition, briefly disturbing the single-writer property mid-flight), so it should only be done
 * on a DRAINED topic in production. The example deploys fresh ({@code docker compose down -v} drops the
 * broker volume), so this is a one-time create. Replication is 1 for the single-broker dev/Redpanda broker;
 * PRODUCTION sets a higher replication factor at the broker / topic-provisioning layer.
 */
@Configuration
class SagaTopicsConfig {

    @Bean
    NewTopic postingRequestedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("posting-requested")
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic ledgerPostedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("ledger-posted")
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic postingRejectedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("posting-rejected")
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
