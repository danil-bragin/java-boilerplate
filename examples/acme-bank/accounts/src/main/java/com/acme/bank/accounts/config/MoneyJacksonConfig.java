package com.acme.bank.accounts.config;

import com.acme.money.Assets;
import com.acme.money.Money;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Teaches Jackson how to round-trip {@link Money} for the Modulith outbox (event_publication). The
 * wire format is {@code {"amount":"100","asset":"USD"}} — the same string representation used by
 * {@link com.acme.bank.contracts.MoneyMapper}.
 */
@Configuration
class MoneyJacksonConfig {

    @Bean
    SimpleModule moneyModule() {
        SimpleModule module = new SimpleModule("MoneyModule");
        module.addSerializer(Money.class, new MoneySerializer());
        module.addDeserializer(Money.class, new MoneyDeserializer());
        return module;
    }

    private static class MoneySerializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money money, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("amount", money.toAmountString());
            gen.writeStringField("asset", money.asset().code());
            gen.writeEndObject();
        }
    }

    private static class MoneyDeserializer extends JsonDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            com.fasterxml.jackson.databind.node.ObjectNode node = p.readValueAsTree();
            String amount = node.get("amount").asText();
            String asset = node.get("asset").asText();
            return Money.of(amount, Assets.of(asset));
        }
    }
}
