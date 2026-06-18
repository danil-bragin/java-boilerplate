package com.acme.bank.transfers.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * BANK-16: declares EVERY saga topic that transfers PRODUCES OR CONSUMES as a multi-partition topic.
 * Spring's auto-configured {@code KafkaAdmin} creates/grows each {@link NewTopic} bean to the declared
 * partition count on context refresh — BEFORE any listener subscribes.
 *
 * <p>transfers produces {@code transfer-requested}, {@code posting-requested}, {@code transfer-completed}
 * and {@code transfer-failed}, and consumes {@code transfer-screened}, {@code ledger-posted} and
 * {@code posting-rejected}. Declaring a topic on EVERY service that touches it (producer AND consumers) is
 * the deterministic-provisioning pattern from BANK-15: a consumer that starts before the producer would
 * otherwise auto-create the topic at the broker default (1 partition) — a silent throughput funnel. With
 * its own {@link NewTopic}, each service's {@code KafkaAdmin} provisions/grows the topic to the declared
 * count regardless of start order, and consumers set {@code allow.auto.create.topics=false} so a missing
 * topic is a loud error rather than a 1-partition funnel. Concurrent identical declarations across
 * services are idempotent ({@code KafkaAdmin} create-or-modify).
 *
 * <p>Partition count comes from the shared {@code acme.bank.topics.partitions} (default 6). Keying is
 * unchanged from BANK-15 ({@code transferId} for most topics; SOURCE account for {@code posting-requested}),
 * so per-entity ordering and all dedup/anchor/lock guarantees hold; partitions add parallelism ACROSS keys.
 *
 * <p>NOTE: increasing partitions on an EXISTING keyed topic re-maps keys (a key could move to a different
 * partition, briefly disturbing per-key serialization mid-flight), so it should only be done on a DRAINED
 * topic in production. The example deploys fresh ({@code docker compose down -v} drops the broker volume),
 * so this is a one-time create. Replication is 1 for the single-broker dev/Redpanda broker; PRODUCTION
 * sets a higher replication factor at the broker / topic-provisioning layer.
 */
@Configuration
class SagaTopicsConfig {

    @Bean
    NewTopic transferRequestedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("transfer-requested")
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic postingRequestedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("posting-requested")
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic transferCompletedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("transfer-completed")
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic transferFailedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("transfer-failed")
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic transferScreenedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("transfer-screened")
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
