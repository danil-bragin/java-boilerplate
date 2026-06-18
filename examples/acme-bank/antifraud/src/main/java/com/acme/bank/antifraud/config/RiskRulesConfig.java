package com.acme.bank.antifraud.config;

import com.acme.bank.antifraud.domain.RiskEngine;
import com.acme.bank.antifraud.domain.RiskRules;
import com.acme.money.Assets;
import com.acme.money.Money;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the RiskEngine bean, with strict/standard rules toggled by the {@code antifraud-strict} feature flag. */
@Configuration
class RiskRulesConfig {

    @Bean
    FeatureProvider featureProvider() {
        Flag<Boolean> strictFlag = Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant("off")
                .build();
        return new InMemoryProvider(Map.of("antifraud-strict", strictFlag));
    }

    @Bean
    RiskEngine riskEngine(Client featureFlagsClient) {
        boolean strict = featureFlagsClient.getBooleanValue("antifraud-strict", false);
        RiskRules rules = strict
                ? new RiskRules(Money.of("1000", Assets.USD), 5)
                : new RiskRules(Money.of("10000", Assets.USD), 5);
        return new RiskEngine(rules);
    }
}
