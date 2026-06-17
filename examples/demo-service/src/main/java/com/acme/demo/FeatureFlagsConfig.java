package com.acme.demo;

import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies an in-memory OpenFeature provider with one demo flag (overrides the starter NoOpProvider). */
@Configuration
public class FeatureFlagsConfig {

    @Bean
    public FeatureProvider featureProvider() {
        Flag<Boolean> newCheckout = Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant("on")
                .build();
        return new InMemoryProvider(Map.of("new-checkout", newCheckout));
    }
}
