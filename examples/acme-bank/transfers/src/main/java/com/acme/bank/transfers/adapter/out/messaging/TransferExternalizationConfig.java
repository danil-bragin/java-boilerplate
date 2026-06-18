package com.acme.bank.transfers.adapter.out.messaging;

import com.acme.bank.transfers.domain.PostingRequestedEvent;
import com.acme.bank.transfers.domain.TransferCompletedEvent;
import com.acme.bank.transfers.domain.TransferFailedEvent;
import com.acme.bank.transfers.domain.TransferRequested;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/**
 * Maps all transfer domain events to their Avro contracts and routes them to the appropriate topics.
 */
@Configuration
class TransferExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof TransferRequested
                        || event instanceof PostingRequestedEvent
                        || event instanceof TransferCompletedEvent
                        || event instanceof TransferFailedEvent)
                .mapping(TransferRequested.class, TransferAvroMapper::toAvro)
                .mapping(PostingRequestedEvent.class, TransferAvroMapper::toAvro)
                .mapping(TransferCompletedEvent.class, TransferAvroMapper::toAvro)
                .mapping(TransferFailedEvent.class, TransferAvroMapper::toAvro)
                .route(TransferRequested.class, event -> RoutingTarget.forTarget("transfer-requested")
                        .andKey(event.transferId()))
                // BANK-15: key posting-requested by the SOURCE account, not the transferId. A
                // source-account key → one partition → one consumer thread → a single writer per
                // account in steady state (no lock contention). The BANK-11 pessimistic lock and the
                // BANK-1 posting-PK anchor remain as correctness backstops at the rebalance edge.
                .route(PostingRequestedEvent.class, event -> RoutingTarget.forTarget("posting-requested")
                        .andKey(event.sourceAccountId()))
                .route(TransferCompletedEvent.class, event -> RoutingTarget.forTarget("transfer-completed")
                        .andKey(event.transferId()))
                .route(TransferFailedEvent.class, event -> RoutingTarget.forTarget("transfer-failed")
                        .andKey(event.transferId()))
                .build();
    }
}
