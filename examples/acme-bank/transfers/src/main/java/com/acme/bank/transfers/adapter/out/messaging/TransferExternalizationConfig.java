package com.acme.bank.transfers.adapter.out.messaging;

import com.acme.bank.transfers.domain.TransferRequested;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/** Maps the domain {@code TransferRequested} to the Avro contract and routes it to the {@code transfer-requested} topic. */
@Configuration
class TransferExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof TransferRequested)
                .mapping(TransferRequested.class, TransferAvroMapper::toAvro)
                .route(TransferRequested.class, event -> RoutingTarget.forTarget("transfer-requested")
                        .andKey(event.transferId()))
                .build();
    }
}
