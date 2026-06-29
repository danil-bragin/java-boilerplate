package com.acme.bank.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.money.Assets;
import com.acme.money.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Guards the shared Money serde extracted from the three per-service {@code MoneyJacksonConfig} copies:
 * (1) the auto-config must contribute a single {@link MoneyJacksonModule} bean (so Spring Boot's
 * {@code JacksonAutoConfiguration} folds it into the application {@code ObjectMapper}), and (2) the JSON
 * wire format ({@code {"amount":...,"asset":...}}) must stay byte-for-byte what the services (and the
 * Modulith outbox) emitted before. A drift in either would corrupt event_publication payloads.
 */
class MoneyJacksonAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MoneyJacksonAutoConfiguration.class));

    @Test
    void autoConfigContributesSingleModuleBean() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MoneyJacksonModule.class));
    }

    @Test
    void serviceProvidedModuleWins() {
        // @ConditionalOnMissingBean: a service may override the canonical module without a bean clash.
        runner.withBean("custom", MoneyJacksonModule.class, MoneyJacksonModule::new)
                .run(ctx -> assertThat(ctx).hasSingleBean(MoneyJacksonModule.class));
    }

    @Test
    void wireFormatIsUnchanged() throws Exception {
        // Mirrors how JacksonAutoConfiguration folds the module into the app ObjectMapper.
        ObjectMapper mapper = new ObjectMapper().registerModule(new MoneyJacksonModule());

        Money money = Money.of("100.50", Assets.of("USD"));
        String json = mapper.writeValueAsString(money);

        // Exact wire format — must match the former MoneyJacksonConfig and MoneyMapper (Avro) string form.
        // toAmountString() strips trailing zeros (value-based, scale-insensitive): 100.50 -> 100.5.
        assertThat(json).isEqualTo("{\"amount\":\"100.5\",\"asset\":\"USD\"}");
        // Round-trip is value-equal (Money equality is scale-insensitive).
        assertThat(mapper.readValue(json, Money.class)).isEqualTo(money);
    }

    /**
     * The real money-safety guard (per adversarial review): proves the module is actually folded into the
     * application {@code ObjectMapper} by Spring Boot's own {@link JacksonAutoConfiguration} — exactly how
     * the bank services (web apps, so spring-web is present) build it — not merely registered as a bean. If
     * auto-wiring silently failed, the Modulith outbox would emit malformed Money in event_publication.
     */
    @Test
    void moduleIsFoldedIntoSpringBootObjectMapper() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(JacksonAutoConfiguration.class, MoneyJacksonAutoConfiguration.class))
                .run(ctx -> {
                    ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
                    Money money = Money.of("100.50", Assets.of("USD"));
                    assertThat(mapper.writeValueAsString(money)).isEqualTo("{\"amount\":\"100.5\",\"asset\":\"USD\"}");
                    assertThat(mapper.readValue("{\"amount\":\"100.5\",\"asset\":\"USD\"}", Money.class))
                            .isEqualTo(money);
                });
    }
}
