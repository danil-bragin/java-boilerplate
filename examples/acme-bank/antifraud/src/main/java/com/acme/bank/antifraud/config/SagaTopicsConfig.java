package com.acme.bank.antifraud.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * BANK-16: declares EVERY saga topic that antifraud PRODUCES OR CONSUMES as a multi-partition topic.
 * Spring's auto-configured {@code KafkaAdmin} creates/grows each {@link NewTopic} bean to the declared
 * partition count on context refresh — BEFORE the listener subscribes.
 *
 * <p>antifraud consumes {@code transfer-requested} and produces {@code transfer-screened}. Declaring the
 * consumed topic here too is the deterministic-provisioning pattern from BANK-15: with its own
 * {@link NewTopic} antifraud never subscribes to an auto-created 1-partition topic regardless of whether
 * transfers (the producer) is up, and {@code allow.auto.create.topics=false} turns a missing topic into a
 * loud error rather than a 1-partition throughput funnel. Concurrent identical declarations across services
 * are idempotent ({@code KafkaAdmin} create-or-modify).
 *
 * <p>Partition count comes from the shared {@code acme.bank.topics.partitions} (default 6). Keying is
 * unchanged ({@code transferId}), so per-transfer ordering and the inbox-dedup guarantee hold; partitions
 * add parallelism ACROSS transfers.
 *
 * <p>NOTE: increasing partitions on an EXISTING keyed topic re-maps keys, so it should only be done on a
 * DRAINED topic in production. The example deploys fresh ({@code docker compose down -v}), so this is a
 * one-time create. Replication is 1 for the single-broker dev/Redpanda broker; PRODUCTION sets a higher
 * replication factor at the broker / topic-provisioning layer.
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
    NewTopic transferScreenedTopic(@Value("${acme.bank.topics.partitions:6}") int partitions) {
        return TopicBuilder.name("transfer-screened")
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
