package com.acme.bank.accounts.config;

import com.acme.bank.accounts.domain.LedgerPostedEvent;
import com.acme.bank.accounts.domain.PostingRejectedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/**
 * Maps accounts domain events ({@code LedgerPostedEvent}, {@code PostingRejectedEvent}) to their
 * Avro contracts and routes them to the appropriate topics.
 */
@Configuration
class AccountsExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof LedgerPostedEvent || event instanceof PostingRejectedEvent)
                .mapping(LedgerPostedEvent.class, e -> com.acme.bank.contracts.avro.LedgerPosted.newBuilder()
                        .setTransferId(e.transferId())
                        .build())
                .mapping(PostingRejectedEvent.class, e -> com.acme.bank.contracts.avro.PostingRejected.newBuilder()
                        .setTransferId(e.transferId())
                        .setReason(e.reason())
                        .build())
                .route(LedgerPostedEvent.class, e -> RoutingTarget.forTarget("ledger-posted")
                        .andKey(e.transferId()))
                .route(PostingRejectedEvent.class, e -> RoutingTarget.forTarget("posting-rejected")
                        .andKey(e.transferId()))
                .build();
    }
}
