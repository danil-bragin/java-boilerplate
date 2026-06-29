package com.acme.bank.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers the canonical {@link MoneyJacksonModule} so every bank service serializes {@code Money}
 * identically over the Modulith outbox JSON — no per-service {@code MoneyJacksonConfig} copies.
 *
 * <p>Ordered before {@link JacksonAutoConfiguration} so the module is present when the
 * {@code ObjectMapper} is built. Gated on Jackson being on the classpath; backs off if a service
 * supplies its own {@link MoneyJacksonModule} bean.
 */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
public class MoneyJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MoneyJacksonModule moneyJacksonModule() {
        return new MoneyJacksonModule();
    }
}
