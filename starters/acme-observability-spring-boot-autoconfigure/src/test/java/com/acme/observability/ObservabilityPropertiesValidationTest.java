package com.acme.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves the {@code @Validated} fail-fast contract: invalid config aborts context startup. */
class ObservabilityPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropsConfig.class);

    @EnableConfigurationProperties(ObservabilityProperties.class)
    static class PropsConfig {}

    @Test
    void validConfigStartsContext() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void invalidLockDurationFailsContextStartup() {
        runner.withPropertyValues("acme.observability.scheduler-lock.default-lock-at-most-for=PT0S")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
