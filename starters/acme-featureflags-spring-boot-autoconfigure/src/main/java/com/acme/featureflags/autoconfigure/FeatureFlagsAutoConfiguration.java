package com.acme.featureflags.autoconfigure;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires an OpenFeature {@link Client} over an overridable {@link FeatureProvider}.
 * The default is a {@link NoOpProvider}; a consumer supplies their own provider bean to drive
 * real flag values.
 */
@AutoConfiguration
@ConditionalOnClass(OpenFeatureAPI.class)
public class FeatureFlagsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeatureProvider featureProvider() {
        return new NoOpProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public Client featureFlagsClient(FeatureProvider provider) {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        try {
            api.setProviderAndWait(provider);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise OpenFeature provider", e);
        }
        return api.getClient();
    }
}
