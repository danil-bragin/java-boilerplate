package com.acme.bank.gateway.config;

import com.acme.money.Assets;
import com.acme.persistence.AssetLookup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@link AssetLookup} used to rehydrate persisted {@code MoneyAmount} columns to {@code Money}. */
@Configuration
public class MoneyConfig {

    @Bean
    AssetLookup assetLookup() {
        return Assets::of;
    }
}
