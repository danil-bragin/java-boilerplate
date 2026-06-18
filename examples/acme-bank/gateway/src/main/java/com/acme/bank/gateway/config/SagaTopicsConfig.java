package com.acme.bank.gateway.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * BANK-16: declares EVERY saga topic that the gateway projection CONSUMES as a multi-partition topic.
 * Spring's auto-configured {@code KafkaAdmin} creates/grows each {@link NewTopic} bean to the declared
 * partition count on context refresh — BEFORE the projection listeners subscribe.
 *
 * <p>The gateway read-model projection consumes {@code transfer-requested}, {@code transfer-screened},
 * {@code transfer-completed} and {@code transfer-failed} (it produces no saga topic). Declaring the consumed
 * topics here is the deterministic-provisioning pattern from BANK-15: with its own {@link NewTopic} beans the
 * gateway never subscribes to auto-created 1-partition topics regardless of producer start order, and
 * {@code allow.auto.create.topics=false} turns a missing topic into a loud error rather than a 1-partition
 * throughput funnel. Concurrent identical declarations across services are idempotent ({@code KafkaAdmin}
 * create-or-modify).
 *
 * <p>Partition count comes from the shared {@code acme.bank.topics.partitions} (default 6). Keying is
 * unchanged ({@code transferId}), so per-transfer ordering of projection updates holds; partitions add
 * parallelism ACROSS transfers. The projection's {@code auto-offset-reset=earliest} (it rebuilds the read
 * model from the log) is unchanged.
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
}
