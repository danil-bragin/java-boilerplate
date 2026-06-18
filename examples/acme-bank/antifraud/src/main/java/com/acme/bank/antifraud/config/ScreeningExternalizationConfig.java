package com.acme.bank.antifraud.config;

import com.acme.bank.antifraud.adapter.out.messaging.ScreeningAvroMapper;
import com.acme.bank.antifraud.domain.TransferScreened;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/** Maps the domain {@code TransferScreened} to the Avro contract and routes it to the {@code transfer-screened} topic. */
@Configuration
class ScreeningExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof TransferScreened)
                .mapping(TransferScreened.class, ScreeningAvroMapper::toAvro)
                .route(TransferScreened.class, event -> RoutingTarget.forTarget("transfer-screened")
                        .andKey(event.transferId()))
                .build();
    }
}
